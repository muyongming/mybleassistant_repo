package com.fastble.bledebugassistant.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleRxEngine;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.LogItem;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.ToastUtil;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 从机（设备）模式：手机作为 GATT Server + 广播。
 *
 * FastBle 是“主机/中央设备”库，从机部分直接用 Android 原生 API 实现，
 * 但收发内核与主机模式完全一致：
 * · 发送：分包（MTU-3）+ 单包串行 + onNotificationSent 回调才发下一包 → 不丢包
 * · 接收：onCharacteristicWriteRequest 只入队，消费线程批量展示 → 不丢包
 */
public class PeripheralActivity extends AppCompatActivity {

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID CHAR_UUID =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final int REQ_PERM = 2001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BleRxEngine rxEngine = new BleRxEngine();
    private final AtomicLong txBytes = new AtomicLong();

    private BluetoothGattServer server;
    private BluetoothGattCharacteristic txChar;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothDevice host;
    private volatile int mtu = 23;
    private boolean broadcasting;

    private final LinkedBlockingQueue<byte[]> notifyQueue = new LinkedBlockingQueue<>();
    private volatile boolean notifyRunning;
    private Thread notifyWorker;
    private volatile CountDownLatch notifyLatch;

    private TextView tvHost, tvCount, tvRecv;
    private EditText etSend;
    private android.widget.Button btnBroadcast;

    private final StringBuilder display = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tvCount.setText(String.format(Locale.US, "MTU: %d    单包: %d B    RX: %s    TX: %s    RX速度: %.1f B/s",
                    mtu, Math.max(20, mtu - 3),
                    HexUtil.toReadableByteCount(rxEngine.getTotalBytes()),
                    HexUtil.toReadableByteCount(txBytes.get()),
                    rxEngine.getBps()));
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_peripheral);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.device_mode_title);
        }

        tvHost = findViewById(R.id.tvHost);
        tvCount = findViewById(R.id.tvCount);
        tvRecv = findViewById(R.id.tvRecv);
        etSend = findViewById(R.id.etSend);
        btnBroadcast = findViewById(R.id.btnBroadcast);

        btnBroadcast.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (broadcasting) closeBroadcast();
                else openBroadcast();
            }
        });
        findViewById(R.id.btnSend).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                sendFromEditText();
            }
        });

        rxEngine.setHex(true);
        rxEngine.start(new BleRxEngine.Listener() {
            @Override
            public void onBatch(final String text, long totalBytes, double bps) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        display.append(text.trim()).append('\n');
                        if (display.length() > 64 * 1024) {
                            display.delete(0, display.length() - 64 * 1024);
                        }
                        tvRecv.setText(display.toString());
                    }
                });
            }
        });

        if (!checkPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERM);
        }
        ui.post(tick);
    }

    // ------------------------------------------------------------ 权限

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean checkPermissions() {
        for (String p : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ToastUtil.show(this, getString(R.string.permission_denied));
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------ 广播

    private void openBroadcast() {
        if (!checkPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERM);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            ToastUtil.show(this, "本设备不支持BLE");
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            ToastUtil.show(this, getString(R.string.bluetooth_off));
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            ToastUtil.show(this, "该设备不支持广播功能");
            return;
        }
        if (!startServer(bm)) return;

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            broadcasting = true;
            btnBroadcast.setText(R.string.broadcast_off);
            LogRepository.get().info("开启广播");
        } catch (Throwable t) {
            ToastUtil.show(this, "开启广播失败: " + t.getMessage());
        }
    }

    private void closeBroadcast() {
        broadcasting = false;
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Throwable ignore) {
        }
        stopNotifyWorker();
        try {
            if (server != null) {
                server.clearServices();
                server.close();
            }
        } catch (Throwable ignore) {
        }
        server = null;
        host = null;
        btnBroadcast.setText(R.string.broadcast_on);
        tvHost.setText(R.string.no_host);
        LogRepository.get().info("关闭广播");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(android.bluetooth.le.AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            broadcasting = false;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    btnBroadcast.setText(R.string.broadcast_on);
                    ToastUtil.show(PeripheralActivity.this, "开启广播失败（错误码 " + errorCode + "）");
                }
            });
        }
    };

    // ------------------------------------------------------------ GATT Server

    private boolean startServer(BluetoothManager bm) {
        try {
            if (server != null) {
                server.clearServices();
                server.close();
            }
            server = bm.openGattServer(this, serverCallback);
            if (server == null) {
                ToastUtil.show(this, "创建 GATT Server 失败");
                return false;
            }
            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
            txChar = new BluetoothGattCharacteristic(CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            | BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                            | BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            txChar.addDescriptor(cccd);
            service.addCharacteristic(txChar);
            server.addService(service);
            startNotifyWorker();
            return true;
        } catch (Throwable t) {
            ToastUtil.show(this, "创建 GATT Server 异常: " + t.getMessage());
            return false;
        }
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvHost.setText(String.format(getString(R.string.host_name),
                                safeName(), host == null ? "" : host.getAddress()));
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                host = null;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvHost.setText(R.string.no_host);
                    }
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int newMtu) {
            mtu = newMtu;
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (value != null && value.length > 0) {
                // 回调线程只入队
                rxEngine.put(value);
            }
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (Throwable ignore) {
                }
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) {
                try {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                } catch (Throwable ignore) {
                }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            byte[] value = new byte[]{0x00, 0x00};
            try {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } catch (Throwable ignore) {
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            CountDownLatch latch = notifyLatch;
            if (latch != null) latch.countDown();
        }
    };

    private String safeName() {
        try {
            BluetoothDevice d = host;
            if (d == null) return "";
            String n = d.getName();
            return TextUtils.isEmpty(n) ? "N/A" : n;
        } catch (Throwable t) {
            return "N/A";
        }
    }

    // ------------------------------------------------------------ 发送（通知）

    private void startNotifyWorker() {
        if (notifyRunning) return;
        notifyRunning = true;
        notifyQueue.clear();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (notifyRunning) {
                    byte[] pkt;
                    try {
                        pkt = notifyQueue.poll(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (pkt == null) continue;
                    BluetoothDevice d = host;
                    BluetoothGattServer s = server;
                    BluetoothGattCharacteristic c = txChar;
                    if (d == null || s == null || c == null) continue;

                    CountDownLatch latch = new CountDownLatch(1);
                    notifyLatch = latch;
                    boolean accepted;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            // 不依赖返回值类型，真正结果由 onNotificationSent 反馈
                            s.notifyCharacteristicChanged(d, c, false, pkt);
                            accepted = true;
                        } else {
                            c.setValue(pkt);
                            accepted = s.notifyCharacteristicChanged(d, c, false);
                        }
                    } catch (Throwable t1) {
                        accepted = false;
                    }
                    if (accepted) {
                        try {
                            // 等 onNotificationSent 再发下一包，避免丢包
                            latch.await(2000, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignore) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        txBytes.addAndGet(pkt.length);
                    } else {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignore) {
                            break;
                        }
                    }
                }
            }
        }, "peripheral-tx");
        notifyRunning = true;
        notifyWorker = t;
        t.start();
    }

    private void stopNotifyWorker() {
        notifyRunning = false;
        Thread t = notifyWorker;
        if (t != null) t.interrupt();
        notifyWorker = null;
        notifyQueue.clear();
    }

    private void sendFromEditText() {
        if (host == null) {
            ToastUtil.show(this, getString(R.string.no_host));
            return;
        }
        String text = etSend.getText() == null ? "" : etSend.getText().toString();
        if (TextUtils.isEmpty(text)) return;
        byte[] data = HexUtil.asciiToBytes(text);
        int payload = Math.max(20, mtu - 3);
        for (int off = 0; off < data.length; off += payload) {
            int n = Math.min(payload, data.length - off);
            byte[] pkt = new byte[n];
            System.arraycopy(data, off, pkt, 0, n);
            notifyQueue.offer(pkt);
        }
        LogRepository.get().add(LogItem.TYPE_TX, "从机发送: " + text);
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        closeBroadcast();
        rxEngine.stop();
        super.onDestroy();
    }
}
