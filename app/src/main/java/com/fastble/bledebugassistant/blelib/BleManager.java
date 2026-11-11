package com.fastble.bledebugassistant.blelib;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本地 BLE 库：对外 API 与 FastBle(com.clj.fastble) 一一对应，
 * 目的是让工程**不依赖任何非 Google 的远程仓库**，云编译（GitHub Actions / Android Studio）
 * 一次就能出包。
 *
 * 想换回真正的 FastBleLib：
 * 1) app/build.gradle 里恢复 implementation 'com.clj.fastble:FastBleLib:2.3.4'
 * 2) 把 core/BleController.java 的 import 从
 *    com.fastble.bledebugassistant.blelib.BleManager.*   改回   com.clj.fastble.*
 * 业务代码（BleTxEngine / BleRxEngine / 所有 UI）一行都不用改。
 *
 * 对上层的行为约定与 FastBle 保持一致：
 * · scan() 在**主线程**回调 onScanStarted / onScanning / onScanFinished
 * · GATT 回调（连接/写/读/通知/MTU）在** BLE 回调线程**直接回调，便于高吞吐
 * · 同一时刻只允许一个 write 操作（符合 Android GATT 单操作模型）
 */
public class BleManager {

    private static final String TAG = "BleManager";
    public static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private static volatile BleManager sInstance;

    public static BleManager getInstance() {
        if (sInstance == null) {
            synchronized (BleManager.class) {
                if (sInstance == null) sInstance = new BleManager();
            }
        }
        return sInstance;
    }

    private BleManager() {
    }

    private Application app;
    private BluetoothAdapter adapter;
    private boolean logEnabled = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------------- 扫描状态
    private BleScanRuleConfig scanRule;
    private BleScanCallback activeScanCallback;
    private ScanCallback platformScanCallback;
    private final Map<String, BleDevice> scanResults = new LinkedHashMap<>();
    private final Runnable scanStopTask = new Runnable() {
        @Override
        public void run() {
            cancelScan();
        }
    };

    // ---------------- 连接状态
    private BluetoothGatt gatt;
    private BleDevice connectedDevice;
    private BleGattCallback gattCallback;
    private BleWriteCallback pendingWrite;
    private BleReadCallback pendingRead;
    private BleNotifyCallback pendingDescriptorCallback;
    private String pendingDescriptorKey;
    private final Map<String, BleNotifyCallback> notifyCallbacks = new HashMap<>();

    // ================================================================ 初始化

    public BleManager init(Application application) {
        this.app = application;
        BluetoothManager bm = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        return this;
    }

    public BleManager enableLog(boolean enable) {
        this.logEnabled = enable;
        return this;
    }

    private void log(String msg) {
        if (logEnabled) Log.d(TAG, msg);
    }

    public boolean isBlueEnable() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public void initScanRule(BleScanRuleConfig config) {
        this.scanRule = config;
    }

    // ================================================================ 扫描

    public void scan(BleScanCallback callback) {
        if (callback == null) return;
        if (app == null || adapter == null || !adapter.isEnabled()) {
            callback.onScanStarted(false);
            return;
        }
        BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (Throwable t) {
            scanner = null;
        }
        if (scanner == null) {
            callback.onScanStarted(false);
            return;
        }
        activeScanCallback = callback;
        scanResults.clear();

        platformScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult r : results) handleScanResult(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                final BleScanCallback cb = activeScanCallback;
                log("scan failed: " + errorCode);
                if (cb != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onScanStarted(false);
                        }
                    });
                }
            }
        };

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, platformScanCallback);
        } catch (Throwable t) {
            log("startScan error: " + t.getMessage());
            callback.onScanStarted(false);
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (activeScanCallback != null) activeScanCallback.onScanStarted(true);
            }
        });

        main.removeCallbacks(scanStopTask);
        if (scanRule != null && scanRule.getScanTimeOut() > 0) {
            main.postDelayed(scanStopTask, scanRule.getScanTimeOut());
        }
    }

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        final BleDevice device = toBleDevice(result);
        if (device == null) return;
        scanResults.put(device.getMac(), device);
        final BleScanCallback cb = activeScanCallback;
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (activeScanCallback != null) activeScanCallback.onScanning(device);
            }
        });
    }

    private BleDevice toBleDevice(ScanResult result) {
        BleDevice d = new BleDevice();
        BluetoothDevice dev = result.getDevice();
        d.device = dev;
        d.rssi = result.getRssi();
        try {
            d.mac = dev.getAddress();
        } catch (Throwable ignore) {
        }
        byte[] bytes = null;
        String name = null;
        if (result.getScanRecord() != null) {
            bytes = result.getScanRecord().getBytes();
            name = result.getScanRecord().getDeviceName();
        }
        d.scanRecord = new ScanRecord(bytes);
        if (TextUtils.isEmpty(name)) name = d.scanRecord.getDeviceName();
        if (TextUtils.isEmpty(name)) {
            try {
                name = dev.getName();
            } catch (Throwable ignore) {
            }
        }
        d.name = name;
        return d;
    }

    public void cancelScan() {
        final BleScanCallback cb = activeScanCallback;
        activeScanCallback = null;
        main.removeCallbacks(scanStopTask);
        try {
            if (platformScanCallback != null && adapter != null) {
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) scanner.stopScan(platformScanCallback);
            }
        } catch (Throwable ignore) {
        }
        platformScanCallback = null;
        if (cb != null) {
            final List<BleDevice> list = new ArrayList<>(scanResults.values());
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.onScanFinished(list);
                }
            });
        }
    }

    // ================================================================ 连接

    public void connect(String mac, BleGattCallback callback) {
        if (TextUtils.isEmpty(mac)) {
            if (callback != null) callback.onConnectFail(new BleException("MAC address is invalid"));
            return;
        }
        if (adapter == null) {
            if (callback != null) callback.onConnectFail(new BleException("BluetoothAdapter is null"));
            return;
        }
        this.gattCallback = callback;
        if (callback != null) callback.onStartConnect();

        BleDevice device = new BleDevice();
        device.mac = mac;
        connectedDevice = device;
        try {
            BluetoothDevice remote = adapter.getRemoteDevice(mac);
            device.device = remote;
            try {
                device.name = remote.getName();
            } catch (Throwable ignore) {
            }
            gatt = remote.connectGatt(app, false, innerGattCallback);
            if (gatt == null && callback != null) {
                callback.onConnectFail(new BleException("connectGatt returned null"));
            }
        } catch (Throwable t) {
            if (callback != null) {
                callback.onConnectFail(new BleException("connect fail: " + t.getMessage()));
            }
        }
    }

    public void disconnect(BleDevice device) {
        BluetoothGatt g = gatt;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Throwable ignore) {
            }
        } else {
            clearGattState();
        }
    }

    private void clearGattState() {
        pendingWrite = null;
        pendingRead = null;
        pendingDescriptorCallback = null;
        pendingDescriptorKey = null;
        notifyCallbacks.clear();
    }

    public BluetoothGatt getBluetoothGatt() {
        return gatt;
    }

    public BleDevice getConnectedDevice() {
        return connectedDevice;
    }

    public boolean isConnected() {
        return gatt != null;
    }

    private String keyOf(BluetoothGattCharacteristic ch) {
        if (ch == null) return "";
        String service = ch.getService() == null ? "" : ch.getService().getUuid().toString();
        return service + "|" + ch.getUuid();
    }

    private final BluetoothGattCallback innerGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            log("onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices();
                } catch (Throwable ignore) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                final BleDevice device = connectedDevice;
                try {
                    g.close();
                } catch (Throwable ignore) {
                }
                if (gatt == g) gatt = null;
                clearGattState();
                final BleGattCallback cb = gattCallback;
                if (cb != null) cb.onDisConnected(true, device, g, status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BleGattCallback cb = gattCallback;
            if (cb != null) cb.onConnectSuccess(connectedDevice, g, status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            BleWriteCallback cb = pendingWrite;
            pendingWrite = null;
            if (cb == null) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cb.onWriteSuccess(1, 1, ch.getValue());
            } else {
                cb.onWriteFailure(new BleException(status, "write fail status=" + status));
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            BleReadCallback cb = pendingRead;
            pendingRead = null;
            if (cb == null) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cb.onReadSuccess(ch.getValue());
            } else {
                cb.onReadFailure(new BleException(status, "read fail status=" + status));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            BleNotifyCallback cb = pendingDescriptorCallback;
            String key = pendingDescriptorKey;
            pendingDescriptorCallback = null;
            pendingDescriptorKey = null;
            if (cb == null) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (key != null) notifyCallbacks.put(key, cb);
                cb.onNotifySuccess();
            } else {
                cb.onNotifyFailure(new BleException(status, "descriptor write fail status=" + status));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            BleNotifyCallback cb = notifyCallbacks.get(keyOf(ch));
            if (cb == null) return;
            byte[] value = ch.getValue();
            if (value == null) return;
            // 只回调一次（onNotify 仅为兼容旧版 FastBle 签名而保留，不重复投递）
            cb.onCharacteristicChanged(value);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            BleGattCallback cb = gattCallback;
            if (cb != null) cb.onMtuChanged(g, mtu, status);
        }

        @Override
        public void onPhyRead(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            BleGattCallback cb = gattCallback;
            if (cb != null) cb.onPhyRead(g, txPhy, rxPhy, status);
        }

        @Override
        public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
            BleGattCallback cb = gattCallback;
            if (cb != null) cb.onPhyUpdate(g, txPhy, rxPhy, status);
        }
    };

    /** 请求读取当前 PHY（结果走 BleGattCallback.onPhyRead），仅 Android 8.0+ */
    public boolean readPhy() {
        BluetoothGatt g = gatt;
        if (g == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            // 注意：BluetoothGatt.readPhy() 返回 void，结果通过 onPhyRead 回调
            g.readPhy();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ 读 / 写

    public void read(BluetoothGattCharacteristic characteristic, BleReadCallback callback) {
        if (characteristic == null || callback == null) return;
        BluetoothGatt g = gatt;
        if (g == null) {
            callback.onReadFailure(new BleException("Connection is null, BT is disconnected"));
            return;
        }
        if (pendingRead != null) {
            callback.onReadFailure(new BleException("read busy"));
            return;
        }
        pendingRead = callback;
        boolean ok;
        try {
            ok = g.readCharacteristic(characteristic);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            pendingRead = null;
            callback.onReadFailure(new BleException("readCharacteristic returned false"));
        }
    }

    /**
     * 写一个完整报文（上层已按 MTU-3 分包）。
     * 同一时刻只允许一个写操作；并发调用会直接失败，由上层重试，避免静默丢包。
     */
    public void write(BluetoothGattCharacteristic characteristic, BleWriteEntity entity,
                      BleWriteCallback callback) {
        if (characteristic == null || entity == null || callback == null) return;
        BluetoothGatt g = gatt;
        if (g == null) {
            callback.onWriteFailure(new BleException("Connection is null, BT is disconnected"));
            return;
        }
        if (pendingWrite != null) {
            callback.onWriteFailure(new BleException("write busy"));
            return;
        }
        pendingWrite = callback;
        boolean ok;
        try {
            characteristic.setValue(entity.getData());
            ok = g.writeCharacteristic(characteristic);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            pendingWrite = null;
            callback.onWriteFailure(new BleException("writeCharacteristic returned false"));
        }
    }

    // ================================================================ 通知

    public void notify(BluetoothGattCharacteristic characteristic, BleNotifyCallback callback) {
        enableNotifyInternal(characteristic, callback, false);
    }

    public void indicate(BluetoothGattCharacteristic characteristic, BleNotifyCallback callback) {
        enableNotifyInternal(characteristic, callback, true);
    }

    private void enableNotifyInternal(BluetoothGattCharacteristic characteristic,
                                      BleNotifyCallback callback, boolean indicate) {
        if (characteristic == null || callback == null) return;
        BluetoothGatt g = gatt;
        if (g == null) {
            callback.onNotifyFailure(new BleException("Connection is null, BT is disconnected"));
            return;
        }
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            callback.onNotifyFailure(new BleException("characteristic has no CCCD descriptor"));
            return;
        }
        try {
            g.setCharacteristicNotification(characteristic, true);
        } catch (Throwable t) {
            callback.onNotifyFailure(new BleException("setCharacteristicNotification fail"));
            return;
        }
        pendingDescriptorCallback = callback;
        pendingDescriptorKey = keyOf(characteristic);
        byte[] value = indicate
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 返回值类型在 API 33 前后不一致，这里不依赖返回值，
                // 真正结果由 onDescriptorWrite 的 status 反馈
                g.writeDescriptor(cccd, value);
                ok = true;
            } else {
                cccd.setValue(value);
                ok = g.writeDescriptor(cccd);
            }
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            pendingDescriptorCallback = null;
            pendingDescriptorKey = null;
            callback.onNotifyFailure(new BleException("writeDescriptor returned false"));
        }
    }

    public void stopNotify(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        notifyCallbacks.remove(keyOf(characteristic));
        BluetoothGatt g = gatt;
        if (g == null) return;
        try {
            g.setCharacteristicNotification(characteristic, false);
        } catch (Throwable ignore) {
        }
        // 有写操作在飞时不抢总线，避免打断正在发送的报文
        if (pendingWrite != null || pendingRead != null) return;
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            } else {
                cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(cccd);
            }
        } catch (Throwable ignore) {
        }
    }

    // ================================================================ 内部数据类

    /** 扫描规则（对应 FastBle 的 BleScanRuleConfig） */
    public static class BleScanRuleConfig {
        private boolean autoConnect = false;
        private int scanTimeOut = 0;

        public boolean isAutoConnect() {
            return autoConnect;
        }

        public int getScanTimeOut() {
            return scanTimeOut;
        }

        public static class Builder {
            private final BleScanRuleConfig config = new BleScanRuleConfig();

            public Builder setAutoConnect(boolean autoConnect) {
                config.autoConnect = autoConnect;
                return this;
            }

            public Builder setScanTimeOut(int scanTimeOut) {
                config.scanTimeOut = scanTimeOut;
                return this;
            }

            public BleScanRuleConfig build() {
                return config;
            }
        }
    }

    /** 扫描结果设备（对应 FastBle 的 BleDevice） */
    public static class BleDevice {
        private BluetoothDevice device;
        private String mac;
        private String name;
        private int rssi;
        private ScanRecord scanRecord;

        public BluetoothDevice getDevice() {
            return device;
        }

        public String getMac() {
            return mac;
        }

        public String getName() {
            return name;
        }

        public int getRssi() {
            return rssi;
        }

        public ScanRecord getScanRecord() {
            return scanRecord;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s(%s) rssi=%d", name, mac, rssi);
        }
    }

    /** 广播包（对应 FastBle 的 ScanRecord） */
    public static class ScanRecord {
        private final byte[] bytes;
        private String deviceName;

        public ScanRecord(byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.deviceName = parseName(this.bytes);
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getDeviceName() {
            return deviceName;
        }

        private static String parseName(byte[] data) {
            int i = 0;
            String shortened = null;
            while (i < data.length) {
                int len = data[i] & 0xFF;
                if (len == 0) break;
                if (i + len >= data.length) break;
                int type = data[i + 1] & 0xFF;
                if (type == 0x09) {
                    return new String(data, i + 2, len - 1);
                } else if (type == 0x08 && shortened == null) {
                    shortened = new String(data, i + 2, len - 1);
                }
                i += len + 1;
            }
            return shortened;
        }
    }

    /** 写报文（对应 FastBle 的 BleWriteEntity） */
    public static class BleWriteEntity {
        private final byte[] data;
        private final boolean split;

        public BleWriteEntity(byte[] data, boolean split) {
            this.data = data;
            this.split = split;
        }

        public byte[] getData() {
            return data;
        }

        public boolean isSplit() {
            return split;
        }
    }

    /** BLE 异常（对应 FastBle 的 BleException） */
    public static class BleException extends Exception {
        private final int code;

        public BleException(String description) {
            super(description);
            this.code = -1;
        }

        public BleException(int code, String description) {
            super(description);
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return getMessage();
        }
    }

    /** 连接回调（对应 FastBle 的 BleGattCallback） */
    public static class BleGattCallback {
        public void onStartConnect() {
        }

        public void onConnectFail(BleException exception) {
        }

        public void onConnectSuccess(BleDevice bleDevice, BluetoothGatt gatt, int status) {
        }

        public void onDisConnected(boolean isActiveDisConnected, BleDevice device,
                                   BluetoothGatt gatt, int status) {
        }

        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        }

        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        }

        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        }
    }

    /** 扫描回调（对应 FastBle 的 BleScanCallback） */
    public static class BleScanCallback {
        public void onScanStarted(boolean success) {
        }

        public void onScanning(BleDevice bleDevice) {
        }

        public void onScanFinished(List<BleDevice> scanResultList) {
        }
    }

    /** 写回调（对应 FastBle 的 BleWriteCallback） */
    public static class BleWriteCallback {
        public void onWriteSuccess(int current, int total, byte[] justWrite) {
        }

        public void onWriteFailure(BleException exception) {
        }
    }

    /** 读回调（对应 FastBle 的 BleReadCallback） */
    public static class BleReadCallback {
        public void onReadSuccess(byte[] data) {
        }

        public void onReadFailure(BleException exception) {
        }
    }

    /** 通知回调（对应 FastBle 的 BleNotifyCallback） */
    public static class BleNotifyCallback {
        public void onNotifySuccess() {
        }

        public void onNotifyFailure(BleException exception) {
        }

        public void onCharacteristicChanged(byte[] data) {
        }

        public void onNotify(byte[] data) {
        }
    }
}
