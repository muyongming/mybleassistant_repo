package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleController;
import com.fastble.bledebugassistant.core.BleRxEngine;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.Beans;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.Prefs;
import com.fastble.bledebugassistant.util.ToastUtil;
import com.fastble.bledebugassistant.util.UuidUtil;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

/**
 * 接收弹窗（对应原版 ReadDialogFragment）：
 * 读取特征值、开关 notify/indicate、HEX/ASCII、实时显示或保存至文件、接收计数与速率。
 *
 * 高速接收时：数据先进 BleRxEngine 队列（回调线程只入队），再由消费线程批量格式化，
 * 因此不会因为 UI 慢而丢包。
 */
public class ReadDialogFragment extends DialogFragment {

    private static final String ARG_SVC = "svc";
    private static final String ARG_CHR = "chr";
    private static final int MAX_DISPLAY_CHARS = 64 * 1024;

    public static void show(FragmentManager fm, BluetoothGattCharacteristic ch) {
        if (ch == null || ch.getService() == null) return;
        Bundle b = new Bundle();
        b.putString(ARG_SVC, ch.getService().getUuid().toString());
        b.putString(ARG_CHR, ch.getUuid().toString());
        ReadDialogFragment f = new ReadDialogFragment();
        f.setArguments(b);
        f.show(fm, "read");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BleRxEngine rx = new BleRxEngine();

    private BluetoothGattCharacteristic characteristic;
    private Beans.ReadConfig config;

    private TextView tvTarget, tvRecvCount, tvRecvSpeed, tvRecvData, tvNotifyState, tvSavePath;
    private RadioButton rbShow, rbSaveFile;
    private CheckBox cbHex, cbNotify;
    private View layoutSavePath;

    private final StringBuilder display = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateCounters();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_BleDebug_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_read, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        Bundle args = getArguments();
        if (args != null) {
            UUID svc = UUID.fromString(args.getString(ARG_SVC, ""));
            UUID chr = UUID.fromString(args.getString(ARG_CHR, ""));
            characteristic = BleController.get().findCharacteristic(svc, chr);
        }

        tvTarget = v.findViewById(R.id.tvTarget);
        tvRecvCount = v.findViewById(R.id.tvRecvCount);
        tvRecvSpeed = v.findViewById(R.id.tvRecvSpeed);
        tvRecvData = v.findViewById(R.id.tvRecvData);
        tvNotifyState = v.findViewById(R.id.tvNotifyState);
        tvSavePath = v.findViewById(R.id.tvSavePath);
        rbShow = v.findViewById(R.id.rbShow);
        rbSaveFile = v.findViewById(R.id.rbSaveFile);
        cbHex = v.findViewById(R.id.cbHex);
        cbNotify = v.findViewById(R.id.cbNotify);
        layoutSavePath = v.findViewById(R.id.layoutSavePath);

        config = Prefs.loadRead(requireContext());
        cbHex.setChecked(config.hex);
        if (config.saveToFile) rbSaveFile.setChecked(true);
        else rbShow.setChecked(true);

        if (characteristic != null) {
            tvTarget.setText("Service: " + UuidUtil.nameOf(characteristic.getService().getUuid())
                    + "\nChar: " + UuidUtil.nameOf(characteristic.getUuid())
                    + "\n" + UuidUtil.describeProperties(characteristic));
        }

        rx.setHex(cbHex.isChecked());
        rx.setDisplayEnabled(!rbSaveFile.isChecked());
        rx.start(new BleRxEngine.Listener() {
            @Override
            public void onBatch(final String text, final long totalBytes, final double bps) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        appendDisplay(text);
                    }
                });
            }
        });

        cbHex.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                rx.setHex(isChecked);
                config.hex = isChecked;
                Prefs.saveRead(requireContext(), config);
            }
        });

        cbNotify.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                toggleNotify(isChecked);
            }
        });

        rbShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                applyReceiveMode(false);
            }
        });
        rbSaveFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                applyReceiveMode(true);
            }
        });

        v.findViewById(R.id.btnRead).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                doRead();
            }
        });
        v.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                display.setLength(0);
                tvRecvData.setText("");
                rx.resetCounter();
                updateCounters();
            }
        });
        v.findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                stopNotify();
                dismissAllowingStateLoss();
            }
        });

        // 打开时先不弹出选文件框，仅恢复显示模式
        layoutSavePath.setVisibility(rbSaveFile.isChecked() ? View.VISIBLE : View.GONE);
        rx.setDisplayEnabled(!rbSaveFile.isChecked());
        ui.post(tick);
        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void applyReceiveMode(boolean save) {
        config.saveToFile = save;
        layoutSavePath.setVisibility(save ? View.VISIBLE : View.GONE);
        rx.setDisplayEnabled(!save);
        if (save) {
            SaveFileDialogFragment.show(getParentFragmentManager(), "recv",
                    new SaveFileDialogFragment.Callback() {
                        @Override
                        public void onFileSelected(File file) {
                            boolean ok = rx.startSave(file);
                            config.filePath = file.getAbsolutePath();
                            tvSavePath.setText(file.getAbsolutePath());
                            if (!ok) {
                                ToastUtil.show(getContext(), "创建文件失败");
                                if (rbShow != null) rbShow.setChecked(true);
                                applyReceiveMode(false);
                                return;
                            }
                            LogRepository.get().info("接收数据保存至 " + file.getAbsolutePath());
                        }
                    });
        } else {
            rx.stopSave();
        }
        Prefs.saveRead(requireContext(), config);
    }

    private void appendDisplay(String text) {
        if (TextUtils.isEmpty(text)) return;
        display.append(text);
        if (display.length() > MAX_DISPLAY_CHARS) {
            // 只截断“屏幕显示缓存”，已接收字节数/速度不受影响，保存文件也不受影响
            display.delete(0, display.length() - MAX_DISPLAY_CHARS);
        }
        tvRecvData.setText(display.toString());
    }

    private void updateCounters() {
        tvRecvCount.setText(HexUtil.toReadableByteCount(rx.getTotalBytes()));
        double bps = rx.getBps();
        tvRecvSpeed.setText(String.format(Locale.US, "%.1f B/s  (%.2f KB/s)", bps, bps / 1024f));
    }

    private void doRead() {
        if (characteristic == null) return;
        LogRepository.get().info("正在读取");
        BleController.get().read(characteristic, new BleController.ReadCallback() {
            @Override
            public void onOk(final byte[] data) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String t = cbHex.isChecked() ? HexUtil.toHexString(data) : HexUtil.toAscii(data);
                        appendDisplay("[read] " + t + "\n");
                        LogRepository.get().info("读取: " + t);
                    }
                });
            }

            @Override
            public void onErr(String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        ToastUtil.show(getContext(), "读取失败");
                        LogRepository.get().error("读取失败");
                    }
                });
            }
        });
    }

    private void toggleNotify(boolean enable) {
        if (characteristic == null) return;
        if (!enable) {
            stopNotify();
            return;
        }
        boolean indicate = UuidUtil.canIndicate(characteristic);
        BleController.get().enableNotify(characteristic, indicate, new BleController.NotifyCallback() {
            @Override
            public void onEnabled(final boolean success, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvNotifyState.setText(success ? "通知已开启" : ("开启失败: " + message));
                        if (!success) {
                            cbNotify.setChecked(false);
                            ToastUtil.show(getContext(), "打开通知失败");
                        } else {
                            LogRepository.get().info("打开通知");
                        }
                    }
                });
            }

            @Override
            public void onData(byte[] data) {
                // 回调线程：只入队，不做任何格式化/UI
                rx.put(data);
            }
        });
    }

    private void stopNotify() {
        if (characteristic != null && BleController.get().isNotifying(
                characteristic.getService().getUuid(), characteristic.getUuid())) {
            BleController.get().disableNotify(characteristic);
            LogRepository.get().info("关闭通知");
        }
        tvNotifyState.setText("通知已关闭");
    }

    @Override
    public void onDestroyView() {
        ui.removeCallbacks(tick);
        rx.stop();
        stopNotify();
        super.onDestroyView();
    }
}
