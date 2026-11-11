package com.fastble.bledebugassistant.ui;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleController;
import com.fastble.bledebugassistant.core.BleRxEngine;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.LogItem;
import com.fastble.bledebugassistant.ui.dialog.MtuDialogFragment;
import com.fastble.bledebugassistant.ui.dialog.PhyDialogFragment;
import com.fastble.bledebugassistant.ui.dialog.SaveFileDialogFragment;
import com.fastble.bledebugassistant.ui.fragment.LogFragment;
import com.fastble.bledebugassistant.ui.fragment.MainFragment;
import com.fastble.bledebugassistant.util.Prefs;
import com.fastble.bledebugassistant.util.ToastUtil;
import com.google.android.material.tabs.TabLayout;

import java.io.File;

/**
 * 设备主页面（对应原版 MainActivity）：服务页 + 日志页，菜单：
 * 连接 / 断开 / 修改MTU / 读取PHY / 设置偏好PHY / 设置连接优先级 / 导出日志。
 */
public class MainActivity extends AppCompatActivity implements BleController.ConnListener {

    /** 全局接收数据管道（所有通知数据统一在此批量处理，再写入日志） */
    private static final BleRxEngine logRx = new BleRxEngine();
    private static boolean logRxStarted = false;

    public static final BleController.GlobalDataListener globalRx =
            new BleController.GlobalDataListener() {
                @Override
                public void onData(String serviceUuid, String characterUuid, byte[] data) {
                    ensureLogRx();
                    logRx.put(data);
                }
            };

    private static synchronized void ensureLogRx() {
        if (logRxStarted) return;
        logRxStarted = true;
        logRx.setHex(true);
        logRx.start(new BleRxEngine.Listener() {
            @Override
            public void onBatch(String text, long totalBytes, double bytesPerSec) {
                String t = text.trim();
                if (t.length() > 0) {
                    LogRepository.get().add(LogItem.TYPE_RX, t);
                }
            }
        });
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private ViewPager viewPager;
    private TabLayout tabLayout;
    private Toolbar toolbar;
    private MainFragment mainFragment;
    private LogFragment logFragment;

    private String mac;
    private String deviceName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ensureLogRx();
        BleController.get().setGlobalDataListener(globalRx);

        Intent intent = getIntent();
        mac = intent.getStringExtra("mac");
        deviceName = intent.getStringExtra("name");
        if (TextUtils.isEmpty(mac)) mac = BleController.get().getMac();

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        updateTitle();

        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (position == 0) {
                    if (mainFragment == null) mainFragment = MainFragment.newInstance();
                    return mainFragment;
                }
                if (logFragment == null) logFragment = LogFragment.newInstance();
                return logFragment;
            }

            @Override
            public int getCount() {
                return 2;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return position == 0 ? getString(R.string.tab_service) : getString(R.string.tab_log);
            }
        });
        tabLayout.setupWithViewPager(viewPager);

        viewPager.post(new Runnable() {
            @Override
            public void run() {
                if (mainFragment != null) mainFragment.refresh();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        BleController.get().setConnListener(this);
        BleController.get().setGlobalDataListener(globalRx);
        if (mainFragment != null) mainFragment.refresh();
        invalidateOptionsMenu();
    }

    private void updateTitle() {
        String name = TextUtils.isEmpty(deviceName) ? BleController.get().getDeviceName() : deviceName;
        if (TextUtils.isEmpty(name)) name = "N/A";
        if (toolbar != null) {
            toolbar.setTitle(name);
            toolbar.setSubtitle((mac == null ? "" : mac) + "  MTU:" + BleController.get().getMtu());
        }
    }

    // ------------------------------------------------------------- 菜单

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean connected = BleController.get().isConnected();
        MenuItem connect = menu.findItem(R.id.action_connect);
        MenuItem disconnect = menu.findItem(R.id.action_disconnect);
        if (connect != null) connect.setVisible(!connected);
        if (disconnect != null) disconnect.setVisible(connected);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connect) {
            connect();
        } else if (id == R.id.action_disconnect) {
            BleController.get().disconnect();
        } else if (id == R.id.action_mtu) {
            showMtuDialog();
        } else if (id == R.id.action_read_phy) {
            readPhy();
        } else if (id == R.id.action_set_phy) {
            showPhyDialog();
        } else if (id == R.id.action_priority) {
            showPriorityDialog();
        } else if (id == R.id.action_save_log) {
            saveLog();
        }
        return true;
    }

    // ------------------------------------------------------------- 连接

    private void connect() {
        if (TextUtils.isEmpty(mac)) return;
        ToastUtil.show(this, getString(R.string.connecting));
        BleController.get().connect(mac, this);
    }

    @Override
    public void onConnecting() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ToastUtil.show(MainActivity.this, getString(R.string.connecting));
            }
        });
    }

    @Override
    public void onConnected() {
        main.post(new Runnable() {
            @Override
            public void run() {
                LogRepository.get().info("连接成功");
                updateTitle();
                if (mainFragment != null) mainFragment.refresh();
                invalidateOptionsMenu();
            }
        });
    }

    @Override
    public void onConnectFailed(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                ToastUtil.show(MainActivity.this, getString(R.string.connect_fail) + ": " + message);
                LogRepository.get().error("连接失败: " + message);
                invalidateOptionsMenu();
            }
        });
    }

    @Override
    public void onDisconnected(final boolean activeDisconnect, final int status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                LogRepository.get().add(LogItem.TYPE_ERROR, "连接断开 status=" + status);
                ToastUtil.show(MainActivity.this, getString(R.string.disconnected));
                updateTitle();
                invalidateOptionsMenu();
            }
        });
    }

    @Override
    public void onMtuChanged(final int mtu) {
        main.post(new Runnable() {
            @Override
            public void run() {
                LogRepository.get().info("MTU=" + mtu + "，单包负载 " + (mtu - 3) + " Byte");
                updateTitle();
                if (mainFragment != null) mainFragment.updateMtuInfo();
            }
        });
    }

    // ------------------------------------------------------------- MTU / PHY / 优先级

    private void showMtuDialog() {
        MtuDialogFragment.show(getSupportFragmentManager(), BleController.get().getMtu(),
                new MtuDialogFragment.Callback() {
                    @Override
                    public void onMtuSelected(int mtu) {
                        boolean ok = BleController.get().requestMtu(mtu);
                        LogRepository.get().info("请求 MTU=" + mtu + (ok ? "" : " 失败"));
                        if (!ok) {
                            ToastUtil.show(MainActivity.this, getString(R.string.mtu_set_fail));
                        }
                    }
                });
    }

    private void readPhy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ToastUtil.show(this, getString(R.string.android_version_limit));
            return;
        }
        // readPhy 是异步的：请求发出后由 onPhyRead 回写 tx/rx PHY
        boolean sent = BleController.get().readPhy(new BleController.PhyCallback() {
            @Override
            public void onResult(final boolean success, final int txPhy, final int rxPhy, final int status) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            String text = "txPhy: " + BleController.phyName(txPhy)
                                    + "   rxPhy: " + BleController.phyName(rxPhy);
                            ToastUtil.show(MainActivity.this, text);
                            LogRepository.get().info(text);
                        } else {
                            ToastUtil.show(MainActivity.this, getString(R.string.phy_read_fail));
                            LogRepository.get().error(getString(R.string.phy_read_fail)
                                    + " status=" + status);
                        }
                    }
                });
            }
        });
        if (!sent) {
            ToastUtil.show(this, getString(R.string.phy_read_fail));
            LogRepository.get().error(getString(R.string.phy_read_fail) + "（未连接或系统不支持）");
        }
    }

    private void showPhyDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ToastUtil.show(this, getString(R.string.android_version_limit));
            return;
        }
        PhyDialogFragment.show(getSupportFragmentManager(), new PhyDialogFragment.Callback() {
            @Override
            public void onPhySelected(int txPhy, int rxPhy, int options) {
                boolean ok = BleController.get().setPreferredPhy(txPhy, rxPhy, options);
                if (ok) {
                    LogRepository.get().info("设置 PHY: tx=" + BleController.phyName(txPhy)
                            + " rx=" + BleController.phyName(rxPhy));
                } else {
                    ToastUtil.show(MainActivity.this, getString(R.string.phy_set_fail));
                    LogRepository.get().error(getString(R.string.phy_set_fail));
                }
            }
        });
    }

    private void showPriorityDialog() {
        final String[] items = new String[]{
                "BALANCED（平衡）",
                "HIGH（高优先级，提高吞吐）",
                "LOW_POWER（低功耗）"};
        final int[] values = new int[]{
                BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
                BluetoothGatt.CONNECTION_PRIORITY_HIGH,
                BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER};
        new AlertDialog.Builder(this)
                .setTitle("设置连接优先级")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean ok = BleController.get().requestConnectionPriority(values[which]);
                        ToastUtil.show(MainActivity.this, ok ? "已请求: " + items[which] : "请求失败");
                        LogRepository.get().info("连接优先级: " + items[which] + (ok ? "" : "（失败）"));
                    }
                })
                .show();
    }

    private void saveLog() {
        final String dump = LogRepository.get().dump();
        if (dump.length() == 0) {
            ToastUtil.show(this, "LogContent is null");
            return;
        }
        SaveFileDialogFragment.show(getSupportFragmentManager(), "log", new SaveFileDialogFragment.Callback() {
            @Override
            public void onFileSelected(File file) {
                boolean ok = SaveFileDialogFragment.writeText(file, dump);
                Toast.makeText(MainActivity.this, ok ? "保存成功\n" + file.getAbsolutePath() : "保存失败",
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
