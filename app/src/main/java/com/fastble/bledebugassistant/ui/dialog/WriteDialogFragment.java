package com.fastble.bledebugassistant.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleController;
import com.fastble.bledebugassistant.core.BleTxEngine;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.Beans;
import com.fastble.bledebugassistant.model.LogItem;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.Prefs;
import com.fastble.bledebugassistant.util.ToastUtil;
import com.fastble.bledebugassistant.util.UuidUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * 发送弹窗（对应原版 WriteDialogFragment）：
 * 单次 / 定时 / 连续 / 文件发送，HEX 开关，实时速度，间隔，随机包长，写类型。
 */
public class WriteDialogFragment extends DialogFragment {

    private static final String ARG_SVC = "svc";
    private static final String ARG_CHR = "chr";
    private static final int REQ_FILE = 3001;

    public static void show(FragmentManager fm, BluetoothGattCharacteristic ch) {
        if (ch == null || ch.getService() == null) return;
        Bundle b = new Bundle();
        b.putString(ARG_SVC, ch.getService().getUuid().toString());
        b.putString(ARG_CHR, ch.getUuid().toString());
        WriteDialogFragment f = new WriteDialogFragment();
        f.setArguments(b);
        f.show(fm, "write");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BleTxEngine tx = new BleTxEngine();

    private BluetoothGattCharacteristic characteristic;
    private Beans.WriteConfig config;

    private TextView tvTarget, tvSendCount, tvSendSpeed, tvFilePath, tvProgress;
    private RadioGroup rgMode, rgWriteType;
    private RadioButton rbSingle, rbTimed, rbCyclic, rbFile, rbWriteDefault, rbWriteNoResponse;
    private View layoutInterval, layoutInput, layoutFile;
    private EditText etInterval, etSendData, etRandomMin, etRandomMax;
    private CheckBox cbHex, cbRandom;
    private ProgressBar progress;
    private Button btnSend;

    private File sendFile;
    private boolean stoppingByUser = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_BleDebug_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_write, container, false);

        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        Bundle args = getArguments();
        if (args != null) {
            UUID svc = UUID.fromString(args.getString(ARG_SVC, ""));
            UUID chr = UUID.fromString(args.getString(ARG_CHR, ""));
            characteristic = BleController.get().findCharacteristic(svc, chr);
        }

        tvTarget = v.findViewById(R.id.tvTarget);
        tvSendCount = v.findViewById(R.id.tvSendCount);
        tvSendSpeed = v.findViewById(R.id.tvSendSpeed);
        tvFilePath = v.findViewById(R.id.tvFilePath);
        tvProgress = v.findViewById(R.id.tvProgress);
        rgMode = v.findViewById(R.id.radioGroupMode);
        rgWriteType = v.findViewById(R.id.radioGroupWriteType);
        rbSingle = v.findViewById(R.id.rbSingle);
        rbTimed = v.findViewById(R.id.rbTimed);
        rbCyclic = v.findViewById(R.id.rbCyclic);
        rbFile = v.findViewById(R.id.rbFile);
        rbWriteDefault = v.findViewById(R.id.rbWriteDefault);
        rbWriteNoResponse = v.findViewById(R.id.rbWriteNoResponse);
        layoutInterval = v.findViewById(R.id.layoutInterval);
        layoutInput = v.findViewById(R.id.layoutInput);
        layoutFile = v.findViewById(R.id.layoutFile);
        etInterval = v.findViewById(R.id.etInterval);
        etSendData = v.findViewById(R.id.etSendData);
        etRandomMin = v.findViewById(R.id.etRandomMin);
        etRandomMax = v.findViewById(R.id.etRandomMax);
        cbHex = v.findViewById(R.id.cbHex);
        cbRandom = v.findViewById(R.id.cbRandom);
        progress = v.findViewById(R.id.progress);
        btnSend = v.findViewById(R.id.btnSend);

        config = Prefs.loadWrite(requireContext());
        bindConfig(v);
        updateTargetInfo();

        rgMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                applyMode();
            }
        });

        v.findViewById(R.id.btnChooseFile).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                chooseFile();
            }
        });
        v.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                etSendData.setText("");
                progress.setProgress(0);
                tvProgress.setText("0%");
                tvSendCount.setText("0 Byte");
                tvSendSpeed.setText("");
            }
        });
        v.findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                stopIfRunning();
                dismissAllowingStateLoss();
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                onSendClicked();
            }
        });

        applyMode();
        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            Window w = d.getWindow();
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void bindConfig(View v) {
        cbHex.setChecked(config.hex);
        etInterval.setText(String.valueOf(config.intervalMs));
        etSendData.setText(config.content);
        etRandomMin.setText(String.valueOf(config.randomMin));
        etRandomMax.setText(String.valueOf(config.randomMax));
        cbRandom.setChecked(config.randomLength);
        if (config.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            rbWriteDefault.setChecked(true);
        } else {
            rbWriteNoResponse.setChecked(true);
        }
        switch (config.mode) {
            case Beans.WriteConfig.MODE_TIMED:
                rbTimed.setChecked(true);
                break;
            case Beans.WriteConfig.MODE_CYCLIC:
                rbCyclic.setChecked(true);
                break;
            case Beans.WriteConfig.MODE_FILE:
                rbFile.setChecked(true);
                break;
            default:
                rbSingle.setChecked(true);
                break;
        }
        if (!TextUtils.isEmpty(config.filePath)) {
            File f = new File(config.filePath);
            if (f.exists()) {
                sendFile = f;
                tvFilePath.setText(f.getAbsolutePath());
            }
        }
    }

    private void updateTargetInfo() {
        if (characteristic == null) {
            tvTarget.setText("未找到特征值");
            btnSend.setEnabled(false);
            return;
        }
        tvTarget.setText("Service: " + UuidUtil.nameOf(characteristic.getService().getUuid())
                + "\nChar: " + UuidUtil.nameOf(characteristic.getUuid())
                + "   " + UuidUtil.describeProperties(characteristic)
                + "\n单包负载: " + BleController.get().getPayloadSize() + " Byte (MTU "
                + BleController.get().getMtu() + ")");
    }

    private void applyMode() {
        int mode = currentMode();
        layoutInterval.setVisibility(mode == Beans.WriteConfig.MODE_SINGLE ? View.GONE : View.VISIBLE);
        layoutInput.setVisibility(mode == Beans.WriteConfig.MODE_FILE ? View.GONE : View.VISIBLE);
        layoutFile.setVisibility(mode == Beans.WriteConfig.MODE_FILE ? View.VISIBLE : View.GONE);
    }

    private int currentMode() {
        int id = rgMode.getCheckedRadioButtonId();
        if (id == R.id.rbTimed) return Beans.WriteConfig.MODE_TIMED;
        if (id == R.id.rbCyclic) return Beans.WriteConfig.MODE_CYCLIC;
        if (id == R.id.rbFile) return Beans.WriteConfig.MODE_FILE;
        return Beans.WriteConfig.MODE_SINGLE;
    }

    // ------------------------------------------------------------- 发送

    private void onSendClicked() {
        if (tx.isRunning()) {
            stoppingByUser = true;
            tx.stop();
            return;
        }
        if (characteristic == null) {
            ToastUtil.show(requireContext(), "未找到特征值");
            return;
        }
        int mode = currentMode();
        int interval = parseInt(etInterval, 10);
        int writeType = rbWriteDefault.isChecked()
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

        if (mode == Beans.WriteConfig.MODE_TIMED && interval < 10) {
            ToastUtil.show(requireContext(), getString(R.string.interval_error));
            return;
        }
        if (mode != Beans.WriteConfig.MODE_FILE && interval < 0) {
            interval = 0;
        }

        saveConfig(mode, interval, writeType);

        if (mode == Beans.WriteConfig.MODE_FILE) {
            if (sendFile == null || !sendFile.exists()) {
                ToastUtil.show(requireContext(), getString(R.string.file_not_exist));
                return;
            }
            int min = parseInt(etRandomMin, 1);
            int max = parseInt(etRandomMax, 20);
            int payload = BleController.get().getPayloadSize();
            if (cbRandom.isChecked()) {
                if (min < 1) min = 1;
                if (max > payload) max = payload;
                if (max < min) max = min;
            }
            BleTxEngine.Request req = BleTxEngine.Request.file(sendFile, true,
                    cbRandom.isChecked(), min, max, interval, writeType);
            startTx(req, "发送文件 " + sendFile.getName() + "（" + HexUtil.toReadableByteCount(sendFile.length()) + "）");
        } else {
            String text = etSendData.getText() == null ? "" : etSendData.getText().toString();
            if (TextUtils.isEmpty(text)) {
                ToastUtil.show(requireContext(), getString(R.string.content_empty));
                return;
            }
            byte[] data;
            if (cbHex.isChecked()) {
                data = HexUtil.hexToBytes(text);
                if (data == null || data.length == 0) {
                    ToastUtil.show(requireContext(), getString(R.string.hex_error));
                    return;
                }
            } else {
                data = HexUtil.asciiToBytes(text);
            }
            if (mode == Beans.WriteConfig.MODE_SINGLE) {
                LogRepository.get().add(LogItem.TYPE_TX, "发送: "
                        + (cbHex.isChecked() ? HexUtil.toHexString(data) : text));
            }
            BleTxEngine.Request req = BleTxEngine.Request.data(data,
                    mode != Beans.WriteConfig.MODE_SINGLE,
                    mode == Beans.WriteConfig.MODE_SINGLE ? 0 : interval, writeType);
            startTx(req, getString(mode == Beans.WriteConfig.MODE_TIMED
                    ? R.string.send_timed : (mode == Beans.WriteConfig.MODE_CYCLIC
                    ? R.string.send_cyclic : R.string.send)));
        }
    }

    private void startTx(BleTxEngine.Request req, String label) {
        stoppingByUser = false;
        progress.setProgress(0);
        tvProgress.setText("0%");
        tvSendCount.setText("0 Byte");
        btnSend.setText(R.string.stop);
        LogRepository.get().info("开始" + label);

        tx.start(characteristic, req, new BleTxEngine.Listener() {
            @Override
            public void onStarted(final long totalBytes) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setProgress(0);
                    }
                });
            }

            @Override
            public void onProgress(final long sentBytes, final long totalBytes, final double bytesPerSec) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tvSendCount.setText(HexUtil.toReadableByteCount(sentBytes));
                        tvSendSpeed.setText(String.format(java.util.Locale.US,
                                "%.1f B/s  (%.2f KB/s)", bytesPerSec, bytesPerSec / 1024f));
                        if (totalBytes > 0) {
                            int p = (int) (sentBytes * 100 / totalBytes);
                            progress.setProgress(Math.min(p, 100));
                            tvProgress.setText(Math.min(p, 100) + "%");
                        }
                    }
                });
            }

            @Override
            public void onFinished(final boolean success, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnSend.setText(R.string.send);
                        if (success) {
                            progress.setProgress(100);
                            tvProgress.setText("100%");
                            ToastUtil.show(getContext(), getString(R.string.send_ok));
                            LogRepository.get().info("发送完成，共 "
                                    + HexUtil.toReadableByteCount(tx.getSentBytes()));
                        } else {
                            if (stoppingByUser) {
                                ToastUtil.show(getContext(), getString(R.string.send_cancel));
                                LogRepository.get().info("取消发送，已发 "
                                        + HexUtil.toReadableByteCount(tx.getSentBytes()));
                            } else {
                                ToastUtil.show(getContext(), getString(R.string.send_fail) + ": " + message);
                                LogRepository.get().error("发送失败: " + message + "，已发 "
                                        + HexUtil.toReadableByteCount(tx.getSentBytes()));
                            }
                        }
                    }
                });
            }
        });
    }

    private void stopIfRunning() {
        if (tx.isRunning()) {
            stoppingByUser = true;
            tx.stop();
        }
    }

    private void saveConfig(int mode, int interval, int writeType) {
        config.mode = mode;
        config.hex = cbHex.isChecked();
        config.intervalMs = interval;
        config.writeType = writeType;
        config.content = etSendData.getText() == null ? "" : etSendData.getText().toString();
        config.randomLength = cbRandom.isChecked();
        config.randomMin = parseInt(etRandomMin, 1);
        config.randomMax = parseInt(etRandomMax, 20);
        if (sendFile != null) config.filePath = sendFile.getAbsolutePath();
        Prefs.saveWrite(requireContext(), config);
    }

    private static int parseInt(EditText et, int def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.length() == 0) return def;
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------- 选择文件

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQ_FILE);
        } catch (Exception e) {
            ToastUtil.show(requireContext(), String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            // 拷贝到缓存目录，发送时可以用文件流顺序读，内存占用恒定
            File out = new File(requireContext().getCacheDir(), "send_file.bin");
            InputStream in = requireContext().getContentResolver().openInputStream(uri);
            if (in == null) return;
            FileOutputStream fos = new FileOutputStream(out, false);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            in.close();
            fos.close();
            sendFile = out;
            tvFilePath.setText(out.getAbsolutePath() + "  ("
                    + HexUtil.toReadableByteCount(out.length()) + ")");
        } catch (Exception e) {
            ToastUtil.show(requireContext(), "读取文件失败: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        stopIfRunning();
        super.onDestroyView();
    }
}
