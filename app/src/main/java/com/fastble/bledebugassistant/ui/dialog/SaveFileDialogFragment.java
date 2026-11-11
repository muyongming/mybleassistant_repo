package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.util.TimeUtil;
import com.fastble.bledebugassistant.util.ToastUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件名输入弹窗（对应原版 SaveFileDialogFragment / SaveLogDialogFragment）。
 */
public class SaveFileDialogFragment extends DialogFragment {

    public interface Callback {
        void onFileSelected(File file);
    }

    private static final Map<String, Callback> PENDING = new ConcurrentHashMap<>();

    public static void show(FragmentManager fm, String purpose, Callback callback) {
        PENDING.put(purpose, callback);
        Bundle b = new Bundle();
        b.putString("purpose", purpose);
        SaveFileDialogFragment f = new SaveFileDialogFragment();
        f.setArguments(b);
        f.show(fm, "save_" + purpose);
    }

    /** 保存目录：优先外部私有目录（无需权限），否则内部目录 */
    public static File baseDir(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        File sub = new File(dir, "BLEDebug");
        if (!sub.exists()) sub.mkdirs();
        return sub;
    }

    public static boolean writeText(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fos = new FileOutputStream(file, false);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"), 64 * 1024);
            w.write(text);
            w.flush();
            w.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_BleDebug_Dialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_save_file, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final String purpose = getArguments() == null ? "log" : getArguments().getString("purpose", "log");
        final File dir = baseDir(requireContext());
        TextView tvDir = v.findViewById(R.id.tvDir);
        tvDir.setText("目录: " + dir.getAbsolutePath());

        final EditText et = v.findViewById(R.id.etFileName);
        String stamp = TimeUtil.fileNameStamp();
        et.setText(("recv".equals(purpose) ? "recv_" : "log_") + stamp
                + ("recv".equals(purpose) ? ".bin" : ".txt"));
        et.setSelection(et.getText().length());

        v.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                PENDING.remove(purpose);
                dismissAllowingStateLoss();
            }
        });
        v.findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                String name = et.getText() == null ? "" : et.getText().toString().trim();
                if (TextUtils.isEmpty(name)) {
                    ToastUtil.show(getContext(), "文件名不能为空");
                    return;
                }
                File file = new File(dir, name);
                Callback cb = PENDING.remove(purpose);
                if (cb != null) cb.onFileSelected(file);
                dismissAllowingStateLoss();
            }
        });
        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
