package com.fastble.bledebugassistant.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleController;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.Beans;
import com.fastble.bledebugassistant.ui.adapter.DeviceAdapter;
import com.fastble.bledebugassistant.ui.dialog.AdvDetailDialogFragment;
import com.fastble.bledebugassistant.ui.dialog.FilterDialogFragment;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.Prefs;
import com.fastble.bledebugassistant.util.ToastUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描页（对应原版 ScanActivity + ScanFragment）：
 * 扫描/已配对 两种模式、名称/地址/广播包/RSSI 过滤、收藏、广播包详情、连接。
 */
public class ScanActivity extends AppCompatActivity implements DeviceAdapter.Listener {

    private static final int REQ_PERM = 1001;

    private final Map<String, Beans.DeviceItem> master = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private DeviceAdapter adapter;
    private Beans.FilterConfig filter;
    private TextView tvStatus, tvEmpty, tabScan, tabBonded;
    private EditText etSearch;

    private volatile boolean scanning = false;
    private boolean bondedMode = false;
    private Dialog connectDialog;

    /** 60s 重新开一轮扫描，避免部分机型长时间扫描后不再上报广播 */
    private final Runnable restartScan = new Runnable() {
        @Override
        public void run() {
            if (!scanning || bondedMode) return;
            BleController.get().stopScan();
            scanning = false;
            startScanInternal();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.scan_title);
        }

        filter = Prefs.loadFilter(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvEmpty = findViewById(R.id.tvEmpty);
        tabScan = findViewById(R.id.tabScan);
        tabBonded = findViewById(R.id.tabBonded);
        etSearch = findViewById(R.id.etSearch);

        RecyclerView rv = findViewById(R.id.recyclerDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(this, this);
        rv.setAdapter(adapter);

        tabScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode(false);
            }
        });
        tabBonded.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchMode(true);
            }
        });
        findViewById(R.id.tvFilter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterDialog();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rebuild();
            }
        });

        // 建立全局数据监听（日志页用）
        BleController.get().setGlobalDataListener(MainActivity.globalRx);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkPermissions()) {
            if (!bondedMode) startScanInternal();
        } else {
            requestPermissions(requiredPermissions(), REQ_PERM);
        }
        updateTabs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_scan, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_filter) {
            showFilterDialog();
        } else if (id == R.id.action_refresh) {
            master.clear();
            adapter.clear();
            if (bondedMode) loadBonded();
            else {
                stopScan();
                startScanInternal();
            }
        } else if (id == R.id.action_device_mode) {
            startActivity(new Intent(this, PeripheralActivity.class));
        } else if (id == R.id.action_favourite) {
            showFavourites();
        } else if (id == R.id.action_info) {
            showPhoneInfo();
        } else if (id == R.id.action_about) {
            showAbout();
        }
        return true;
    }

    // ---------------------------------------------------------------- 权限

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
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
        if (requestCode != REQ_PERM) return;
        boolean all = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) all = false;
        }
        if (all) {
            if (!bondedMode) startScanInternal();
        } else {
            ToastUtil.show(this, getString(R.string.permission_denied));
        }
    }

    // ---------------------------------------------------------------- 扫描

    private void startScanInternal() {
        if (!BleController.get().isBluetoothOn()) {
            ToastUtil.show(this, getString(R.string.bluetooth_off));
            requestEnableBluetooth();
            return;
        }
        if (scanning) return;
        scanning = true;
        updateStatus();
        tvStatus.removeCallbacks(restartScan);
        tvStatus.postDelayed(restartScan, 60000);

        BleController.get().startScan(new BleController.ScanListener() {
            @Override
            public void onScanStarted(boolean success) {
                if (!success) {
                    scanning = false;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            ToastUtil.show(ScanActivity.this, getString(R.string.permission_denied));
                            updateStatus();
                        }
                    });
                }
            }

            @Override
            public void onScanning(final Beans.DeviceItem item) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        handleScanResult(item);
                    }
                });
            }

            @Override
            public void onScanFinished() {
                scanning = false;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        updateStatus();
                    }
                });
            }
        });
    }

    private void stopScan() {
        scanning = false;
        tvStatus.removeCallbacks(restartScan);
        BleController.get().stopScan();
    }

    private void requestEnableBluetooth() {
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(intent);
        } catch (Throwable ignore) {
        }
    }

    private void handleScanResult(Beans.DeviceItem item) {
        item.favourite = Prefs.isFavourite(this, item.mac);
        master.put(item.mac, item);
        if (passes(item)) {
            adapter.upsert(item);
        } else {
            adapter.removeByMac(item.mac);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (bondedMode) {
            tvStatus.setText("已配对设备：" + adapter.getItemCount() + " 个");
        } else {
            tvStatus.setText((scanning ? "扫描中…" : "扫描已停止")
                    + "  已发现 " + master.size() + " 个，显示 " + adapter.getItemCount() + " 个");
        }
        tvEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void rebuild() {
        adapter.clear();
        for (Beans.DeviceItem d : master.values()) {
            if (passes(d)) adapter.upsert(d);
        }
        updateStatus();
    }

    private String keyword() {
        return etSearch.getText() == null ? "" : etSearch.getText().toString().trim();
    }

    private boolean passes(Beans.DeviceItem d) {
        String kw = keyword();
        String name = d.displayName();

        if (!TextUtils.isEmpty(kw)) {
            if (!(name.toLowerCase().contains(kw.toLowerCase())
                    || (d.mac != null && d.mac.toLowerCase().contains(kw.toLowerCase())))) {
                return false;
            }
        }
        if (!TextUtils.isEmpty(filter.macName)
                && !name.toLowerCase().contains(filter.macName.toLowerCase())) {
            return false;
        }
        if (!TextUtils.isEmpty(filter.macMac)
                && (d.mac == null || !d.mac.toLowerCase().contains(filter.macMac.toLowerCase()))) {
            return false;
        }
        if (!TextUtils.isEmpty(filter.broadcast)) {
            String hex = d.scanRecord == null ? "" : HexUtil.toHexStringNoSpace(d.scanRecord);
            if (!hex.toLowerCase().contains(filter.broadcast.toLowerCase())) return false;
        }
        if (filter.rssi > -100 && d.rssi < filter.rssi) return false;
        if (filter.noName && !"N/A".equals(name)) return false;
        if (filter.onlyLike && !Prefs.isFavourite(this, d.mac)) return false;
        if (filter.onlyBLE) {
            try {
                if (d.device != null && d.device.getType() != BluetoothDevice.DEVICE_TYPE_LE) return false;
            } catch (Throwable ignore) {
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 已配对

    private void switchMode(boolean bonded) {
        if (bondedMode == bonded) return;
        bondedMode = bonded;
        adapter.clear();
        updateTabs();
        if (bonded) {
            stopScan();
            loadBonded();
        } else {
            master.clear();
            startScanInternal();
        }
    }

    private void updateTabs() {
        tabScan.setTextColor(getResources().getColor(bondedMode ? R.color.text_secondary : R.color.colorPrimary));
        tabBonded.setTextColor(getResources().getColor(bondedMode ? R.color.colorPrimary : R.color.text_secondary));
        tabScan.setTypeface(null, bondedMode ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        tabBonded.setTypeface(null, bondedMode ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void loadBonded() {
        adapter.clear();
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a == null) return;
            for (BluetoothDevice d : a.getBondedDevices()) {
                Beans.DeviceItem item = new Beans.DeviceItem();
                item.mac = d.getAddress();
                item.name = d.getName();
                item.device = d;
                item.bonded = true;
                item.rssi = 0;
                item.favourite = Prefs.isFavourite(this, item.mac);
                adapter.upsert(item);
            }
        } catch (Throwable t) {
            ToastUtil.show(this, String.valueOf(t.getMessage()));
        }
        updateStatus();
    }

    // ---------------------------------------------------------------- 列表回调

    @Override
    public void onConnect(Beans.DeviceItem item) {
        if (!checkPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERM);
            return;
        }
        if (!BleController.get().isBluetoothOn()) {
            ToastUtil.show(this, getString(R.string.bluetooth_off));
            return;
        }
        connect(item);
    }

    @Override
    public void onStarClick(Beans.DeviceItem item) {
        item.favourite = !item.favourite;
        Prefs.setFavourite(this, item.mac, item.name, item.favourite);
        adapter.notifyDataSetChanged();
        ToastUtil.show(this, getString(item.favourite ? R.string.favourite_add : R.string.favourite_remove));
    }

    @Override
    public void onShowAdv(Beans.DeviceItem item) {
        AdvDetailDialogFragment.show(getSupportFragmentManager(), item);
    }

    // ---------------------------------------------------------------- 连接

    private void connect(final Beans.DeviceItem item) {
        showConnectingDialog();
        LogRepository.get().info("正在连接 " + item.displayName() + " [" + item.mac + "]");
        BleController.get().connect(item.mac, new BleController.ConnListener() {
            @Override
            public void onConnecting() {
            }

            @Override
            public void onConnected() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissConnecting();
                        Prefs.addHistory(ScanActivity.this, item.mac);
                        LogRepository.get().info("连接成功，MTU=" + BleController.get().getMtu());
                        Intent i = new Intent(ScanActivity.this, MainActivity.class);
                        i.putExtra("mac", item.mac);
                        i.putExtra("name", item.name);
                        startActivity(i);
                    }
                });
            }

            @Override
            public void onConnectFailed(final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissConnecting();
                        ToastUtil.show(ScanActivity.this, getString(R.string.connect_fail) + ": " + message);
                        LogRepository.get().error("连接失败: " + message);
                    }
                });
            }

            @Override
            public void onDisconnected(final boolean activeDisconnect, final int status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissConnecting();
                        LogRepository.get().info("连接断开 status=" + status);
                    }
                });
            }

            @Override
            public void onMtuChanged(final int mtu) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        LogRepository.get().info("MTU 更新为 " + mtu);
                    }
                });
            }
        });
    }

    private void showConnectingDialog() {
        dismissConnecting();
        connectDialog = new AlertDialog.Builder(this)
                .setMessage(getString(R.string.connecting))
                .setCancelable(false)
                .create();
        connectDialog.show();
    }

    private void dismissConnecting() {
        if (connectDialog != null) {
            try {
                connectDialog.dismiss();
            } catch (Throwable ignore) {
            }
            connectDialog = null;
        }
    }

    // ---------------------------------------------------------------- 弹窗

    private void showFilterDialog() {
        FilterDialogFragment f = FilterDialogFragment.newInstance(filter);
        f.setListener(new FilterDialogFragment.Listener() {
            @Override
            public void onFilterChanged(Beans.FilterConfig config) {
                filter = config;
                Prefs.saveFilter(ScanActivity.this, config);
                rebuild();
            }
        });
        f.show(getSupportFragmentManager(), "filter");
    }

    private void showFavourites() {
        final List<String> macs = Prefs.favouriteMacs(this);
        if (macs.isEmpty()) {
            ToastUtil.show(this, "暂无收藏设备");
            return;
        }
        final String[] items = new String[macs.size()];
        for (int i = 0; i < macs.size(); i++) {
            Beans.DeviceItem d = master.get(macs.get(i));
            items[i] = (d == null ? "N/A" : d.displayName()) + "\n" + macs.get(i);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_favourite)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Beans.DeviceItem d = master.get(macs.get(which));
                        if (d == null) {
                            d = new Beans.DeviceItem();
                            d.mac = macs.get(which);
                            d.name = "N/A";
                        }
                        connect(d);
                    }
                })
                .show();
    }

    private void showPhoneInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("手机型号: ").append(Build.MODEL).append('\n');
        sb.append("系统版本: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a != null) {
                sb.append("蓝牙地址: ").append(a.getAddress()).append('\n');
                sb.append("蓝牙名称: ").append(a.getName()).append('\n');
                sb.append("支持 BLE: ").append(getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)).append('\n');
            }
        } catch (Throwable ignore) {
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_info)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showAbout() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_save_file, null);
        // 复用简单布局展示关于信息
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setMessage("BLE 调试助手 (FastBle 复刻版)\n\n"
                        + "基于 FastBle 实现，支持：\n"
                        + "· 扫描/已配对/收藏/过滤\n"
                        + "· 服务与特征值浏览(res/indicate/write)\n"
                        + "· 单次/定时/连续/文件发送\n"
                        + "· 接收实时显示或保存文件\n"
                        + "· MTU/PHY/连接优先级设置\n\n"
                        + "高速收发采用串行回调+f流控内核，不丢包。")
                .setPositiveButton(R.string.ok, null)
                .show();
    }
}
