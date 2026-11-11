package com.fastble.bledebugassistant.core;

import android.annotation.TargetApi;
import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Build;
import android.text.TextUtils;

import com.fastble.bledebugassistant.blelib.BleManager;
import com.fastble.bledebugassistant.blelib.BleManager.BleDevice;
import com.fastble.bledebugassistant.blelib.BleManager.BleException;
import com.fastble.bledebugassistant.blelib.BleManager.BleGattCallback;
import com.fastble.bledebugassistant.blelib.BleManager.BleNotifyCallback;
import com.fastble.bledebugassistant.blelib.BleManager.BleReadCallback;
import com.fastble.bledebugassistant.blelib.BleManager.BleScanCallback;
import com.fastble.bledebugassistant.blelib.BleManager.BleScanRuleConfig;
import com.fastble.bledebugassistant.blelib.BleManager.BleWriteCallback;
import com.fastble.bledebugassistant.blelib.BleManager.BleWriteEntity;
import com.fastble.bledebugassistant.model.Beans;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastBle 统一封装（本项目所有与 FastBle 的交互都集中在这一个文件）。
 *
 * 如果更换 FastBle 版本后个别回调签名不一致，只需要修改本文件，
 * 业务代码（BleTxEngine / BleRxEngine / UI）无需改动。
 * 这里回调方法故意不加 @Override，以兼容不同版本中
 * onConnectFail(BleException) 与 onConnectFail(BleDevice, BleException) 两种声明。
 */
public class BleController {

    public static final int DEFAULT_MTU = 23;

    public interface ScanListener {
        void onScanStarted(boolean success);

        void onScanning(Beans.DeviceItem item);

        void onScanFinished();
    }

    public interface ConnListener {
        void onConnecting();

        void onConnected();

        void onConnectFailed(String message);

        void onDisconnected(boolean activeDisconnect, int status);

        void onMtuChanged(int mtu);
    }

    public interface WriteCallback {
        void onOk();

        void onErr(String message);
    }

    public interface ReadCallback {
        void onOk(byte[] data);

        void onErr(String message);
    }

    public interface NotifyCallback {
        void onEnabled(boolean success, String message);

        void onData(byte[] data);
    }

    /** 全局数据接收（日志页用） */
    public interface GlobalDataListener {
        void onData(String serviceUuid, String characterUuid, byte[] data);
    }

    private static final BleController INSTANCE = new BleController();

    public static BleController get() {
        return INSTANCE;
    }

    private volatile BluetoothGatt gatt;
    private volatile BleDevice bleDevice;
    private volatile String mac;
    private volatile int mtu = DEFAULT_MTU;

    private volatile ConnListener connListener;
    private volatile GlobalDataListener globalDataListener;

    private final Map<String, NotifyCallback> notifyCallbacks = new ConcurrentHashMap<>();

    private BleController() {
    }

    public static String key(UUID service, UUID characteristic) {
        return service + "|" + characteristic;
    }

    // ------------------------------------------------------------ 初始化 / 状态

    public void init(Application app) {
        BleManager.getInstance().init(app);
        BleManager.getInstance().enableLog(true);
    }

    public boolean isBluetoothOn() {
        try {
            return BleManager.getInstance().isBlueEnable();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isConnected() {
        return gatt != null;
    }

    public BluetoothGatt getGatt() {
        return gatt;
    }

    public String getMac() {
        return mac;
    }

    public String getDeviceName() {
        BleDevice d = bleDevice;
        return d == null ? null : d.getName();
    }

    public int getMtu() {
        return mtu;
    }

    /** 单包有效负载：MTU - 3（ATT 头） */
    public int getPayloadSize() {
        int n = mtu - 3;
        return n < 20 ? 20 : n;
    }

    public List<BluetoothGattService> getServices() {
        BluetoothGatt g = gatt;
        return g == null ? null : g.getServices();
    }

    public BluetoothGattCharacteristic findCharacteristic(UUID serviceUuid, UUID charUuid) {
        BluetoothGatt g = gatt;
        if (g == null || serviceUuid == null || charUuid == null) return null;
        BluetoothGattService s = g.getService(serviceUuid);
        return s == null ? null : s.getCharacteristic(charUuid);
    }

    public void setGlobalDataListener(GlobalDataListener l) {
        this.globalDataListener = l;
    }

    /** 页面切换时重新指定连接状态监听（不需要重新 connect） */
    public void setConnListener(ConnListener l) {
        this.connListener = l;
    }

    // ------------------------------------------------------------ 扫描

    public void startScan(final ScanListener listener) {
        if (!isBluetoothOn()) {
            listener.onScanStarted(false);
            listener.onScanFinished();
            return;
        }
        BleScanRuleConfig config = new BleScanRuleConfig.Builder()
                .setAutoConnect(false)
                .setScanTimeOut(0)          // 0：不自动停止（由调用方取消）
                .build();
        BleManager.getInstance().initScanRule(config);
        BleManager.getInstance().scan(new BleScanCallback() {
            public void onScanStarted(boolean success) {
                listener.onScanStarted(success);
            }

            public void onScanning(BleDevice bleDevice) {
                listener.onScanning(toItem(bleDevice));
            }

            public void onScanFinished(List<BleDevice> list) {
                listener.onScanFinished();
            }
        });
    }

    public void stopScan() {
        try {
            BleManager.getInstance().cancelScan();
        } catch (Throwable ignore) {
        }
    }

    private static Beans.DeviceItem toItem(BleDevice d) {
        Beans.DeviceItem item = new Beans.DeviceItem();
        item.mac = d.getMac();
        item.name = d.getName();
        if (TextUtils.isEmpty(item.name) && d.getScanRecord() != null) {
            item.name = d.getScanRecord().getDeviceName();
        }
        item.rssi = d.getRssi();
        item.scanRecord = d.getScanRecord() == null ? null : d.getScanRecord().getBytes();
        item.device = d.getDevice();
        item.lastSeen = System.currentTimeMillis();
        try {
            item.bonded = d.getDevice() != null
                    && d.getDevice().getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Throwable ignore) {
        }
        return item;
    }

    // ------------------------------------------------------------ 连接

    public void connect(final String targetMac, final ConnListener listener) {
        if (TextUtils.isEmpty(targetMac)) {
            listener.onConnectFailed("MAC address is invalid");
            return;
        }
        this.mac = targetMac;
        this.connListener = listener;
        // 已经连接着另一台就先断（保持与 FastBle 的连接状态一致）
        try {
            BleDevice old = this.bleDevice;
            if (old != null && !targetMac.equalsIgnoreCase(old.getMac())) {
                BleManager.getInstance().disconnect(old);
                gatt = null;
                bleDevice = null;
                mtu = DEFAULT_MTU;
            }
        } catch (Throwable ignore) {
        }
        BleManager.getInstance().connect(targetMac, new BleGattCallback() {

            public void onStartConnect() {
                if (connListener != null) connListener.onConnecting();
            }

            // 兼容某些版本的签名
            public void onStartConnect(BleDevice device) {
                if (connListener != null) connListener.onConnecting();
            }

            public void onConnectFail(BleException exception) {
                if (connListener != null)
                    connListener.onConnectFailed(describe(exception, "connect fail"));
            }

            public void onConnectFail(BleDevice device, BleException exception) {
                if (connListener != null)
                    connListener.onConnectFailed(describe(exception, "connect fail"));
            }

            public void onConnectSuccess(BleDevice device, BluetoothGatt g, int status) {
                bleDevice = device;
                gatt = g;
                mtu = DEFAULT_MTU;
                if (connListener != null) connListener.onConnected();
            }

            public void onDisConnected(boolean isActiveDisConnected, BleDevice device,
                                       BluetoothGatt g, int status) {
                notifyCallbacks.clear();
                gatt = null;
                bleDevice = null;
                mtu = DEFAULT_MTU;
                if (connListener != null) connListener.onDisconnected(isActiveDisConnected, status);
            }

            // 兼容某些版本的签名
            public void onDisConnected(boolean isActiveDisConnected, BleDevice device, int status) {
                onDisConnected(isActiveDisConnected, device, null, status);
            }

            public void onMtuChanged(BluetoothGatt g, int m, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = m;
                if (connListener != null) connListener.onMtuChanged(mtu);
            }

            public void onPhyRead(BluetoothGatt g, int txPhy, int rxPhy, int status) {
                PhyCallback cb = phyCallback;
                phyCallback = null;
                if (cb != null) cb.onResult(status == BluetoothGatt.GATT_SUCCESS, txPhy, rxPhy, status);
            }
        });
    }

    public void disconnect() {
        try {
            BleDevice d = bleDevice;
            if (d != null) {
                BleManager.getInstance().disconnect(d);
            } else {
                BluetoothGatt g = gatt;
                if (g != null) {
                    g.disconnect();
                    g.close();
                }
                gatt = null;
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------ MTU / PHY / 优先级

    /** @return false 表示请求未能发出 */
    public boolean requestMtu(int newMtu) {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try {
            if (newMtu < 23) newMtu = 23;
            return g.requestMtu(newMtu);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean requestConnectionPriority(int priority) {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        try {
            return g.requestConnectionPriority(priority);
        } catch (Throwable t) {
            return false;
        }
    }

    /** PHY 读取结果回调 */
    public interface PhyCallback {
        void onResult(boolean success, int txPhy, int rxPhy, int status);
    }

    private volatile PhyCallback phyCallback;

    /**
     * 读取当前 PHY（异步）。BluetoothDevice 没有公开的 getPhy()，
     * 只能用 BluetoothGatt.readPhy() + onPhyRead 回调。
     *
     * @return false 表示请求没发出去（未连接 / 系统低于 8.0）
     */
    @TargetApi(Build.VERSION_CODES.O)
    public boolean readPhy(PhyCallback callback) {
        BluetoothGatt g = gatt;
        if (g == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        phyCallback = callback;
        try {
            // 注意：readPhy() 返回 void，结果通过 onPhyRead 回调回写
            g.readPhy();
            return true;
        } catch (Throwable t) {
            phyCallback = null;
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    public boolean setPreferredPhy(int txPhy, int rxPhy, int options) {
        BluetoothGatt g = gatt;
        if (g == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            g.setPreferredPhy(txPhy, rxPhy, options);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String phyName(int phy) {
        switch (phy) {
            case BluetoothDevice.PHY_LE_1M:
                return "LE 1M";
            case BluetoothDevice.PHY_LE_2M:
                return "LE 2M";
            case BluetoothDevice.PHY_LE_CODED:
                return "LE CODED";
            default:
                return "Unknown";
        }
    }

    // ------------------------------------------------------------ 写

    /**
     * 发送一个“已分包好”的报文（长度必须 <= getPayloadSize()）。
     * 完整性与顺序由调用方（BleTxEngine）保证：上一个包回调后才会发下一个。
     */
    public boolean write(BluetoothGattCharacteristic characteristic, byte[] packet, int writeType,
                         final WriteCallback callback) {
        if (characteristic == null || packet == null || packet.length == 0) {
            callback.onErr("characteristic or packet is null");
            return false;
        }
        if (gatt == null) {
            callback.onErr("Connection is null, BT is disconnected");
            return false;
        }
        try {
            characteristic.setWriteType(writeType);
            BleWriteEntity entity = new BleWriteEntity(packet, false);
            BleManager.getInstance().write(characteristic, entity, new BleWriteCallback() {
                public void onWriteSuccess(int current, int total, byte[] justWrite) {
                    callback.onOk();
                }

                public void onWriteFailure(BleException exception) {
                    callback.onErr(describe(exception, "write fail"));
                }
            });
            return true;
        } catch (Throwable t) {
            callback.onErr(String.valueOf(t.getMessage()));
            return false;
        }
    }

    // ------------------------------------------------------------ 读

    public void read(BluetoothGattCharacteristic characteristic, final ReadCallback callback) {
        if (characteristic == null || gatt == null) {
            callback.onErr("Connection is null, BT is disconnected");
            return;
        }
        try {
            BleManager.getInstance().read(characteristic, new BleReadCallback() {
                public void onReadSuccess(byte[] data) {
                    callback.onOk(data);
                }

                public void onReadFailure(BleException exception) {
                    callback.onErr(describe(exception, "read fail"));
                }
            });
        } catch (Throwable t) {
            callback.onErr(String.valueOf(t.getMessage()));
        }
    }

    // ------------------------------------------------------------ 通知

    public void enableNotify(BluetoothGattCharacteristic characteristic, boolean indicate,
                             final NotifyCallback callback) {
        if (characteristic == null || gatt == null) {
            callback.onEnabled(false, "Connection is null, BT is disconnected");
            return;
        }
        final String k = key(characteristic.getService().getUuid(), characteristic.getUuid());
        notifyCallbacks.put(k, callback);
        BleNotifyCallback cb = new BleNotifyCallback() {

            public void onNotifySuccess() {
                NotifyCallback c = notifyCallbacks.get(k);
                if (c != null) c.onEnabled(true, null);
            }

            public void onNotifyFailure(BleException exception) {
                NotifyCallback c = notifyCallbacks.remove(k);
                if (c != null) c.onEnabled(false, describe(exception, "notify fail"));
            }

            public void onCharacteristicChanged(byte[] data) {
                dispatch(k, data);
            }

            // 兼容旧版本 FastBle 的回调名
            public void onNotify(byte[] data) {
                dispatch(k, data);
            }
        };
        try {
            if (indicate) {
                BleManager.getInstance().indicate(characteristic, cb);
            } else {
                BleManager.getInstance().notify(characteristic, cb);
            }
        } catch (Throwable t) {
            notifyCallbacks.remove(k);
            callback.onEnabled(false, String.valueOf(t.getMessage()));
        }
    }

    private void dispatch(String key, byte[] data) {
        NotifyCallback c = notifyCallbacks.get(key);
        if (c != null) {
            c.onData(data);
        }
        GlobalDataListener g = globalDataListener;
        if (g != null) {
            int i = key.indexOf('|');
            String svc = i > 0 ? key.substring(0, i) : "";
            String chr = i > 0 ? key.substring(i + 1) : key;
            g.onData(svc, chr, data);
        }
    }

    public void disableNotify(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        notifyCallbacks.remove(key(characteristic.getService().getUuid(), characteristic.getUuid()));
        try {
            BleManager.getInstance().stopNotify(characteristic);
        } catch (Throwable ignore) {
        }
    }

    public boolean isNotifying(UUID service, UUID characteristic) {
        return notifyCallbacks.containsKey(key(service, characteristic));
    }

    // ------------------------------------------------------------ 工具

    public static String describe(BleException e, String def) {
        if (e == null) return def;
        String d = e.getDescription();
        return TextUtils.isEmpty(d) ? (def + "(" + e.getCode() + ")") : d;
    }
}
