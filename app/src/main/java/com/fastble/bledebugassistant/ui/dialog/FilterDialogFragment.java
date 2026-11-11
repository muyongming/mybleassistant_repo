package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.model.Beans;

/**
 * 扫描过滤弹窗（对应原版 fragment_dialog_filter）。
 * 名称 / 地址 / 广播包 HEX / RSSI 阈值 / 只看收藏 / 只看 LE-only / 只看无名称。
 */
public class FilterDialogFragment extends DialogFragment {

    public interface Listener {
        void onFilterChanged(Beans.FilterConfig config);
    }

    private static final String ARG_CONFIG = "config";

    private Listener listener;
    private Beans.FilterConfig config;

    public static FilterDialogFragment newInstance(Beans.FilterConfig cfg) {
        Bundle b = new Bundle();
        b.putSerializable(ARG_CONFIG, cfg);
        FilterDialogFragment f = new FilterDialogFragment();
        f.setArguments(b);
        return f;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_TITLE, R.style.Theme_BleDebug_Dialog);
        Bundle args = getArguments();
        Object o = args == null ? null : args.getSerializable(ARG_CONFIG);
        config = o instanceof Beans.FilterConfig ? (Beans.FilterConfig) o : new Beans.FilterConfig();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.dialog_filter, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final EditText etName = v.findViewById(R.id.etName);
        final EditText etMac = v.findViewById(R.id.etMac);
        final EditText etAdv = v.findViewById(R.id.etAdv);
        final CheckBox cbNoName = v.findViewById(R.id.cbNoName);
        final CheckBox cbOnlyLike = v.findViewById(R.id.cbOnlyLike);
        final CheckBox cbOnlyBle = v.findViewById(R.id.cbOnlyBle);
        final SeekBar seek = v.findViewById(R.id.seekRssi);
        final TextView tvRssi = v.findViewById(R.id.tvRssi);

        etName.setText(config.macName);
        etMac.setText(config.macMac);
        etAdv.setText(config.broadcast);
        cbNoName.setChecked(config.noName);
        cbOnlyLike.setChecked(config.onlyLike);
        cbOnlyBle.setChecked(config.onlyBLE);
        seek.setProgress(config.rssi + 100);
        tvRssi.setText(config.rssi + " dBm");

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRssi.setText((progress - 100) + " dBm");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        v.findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                Beans.FilterConfig c = new Beans.FilterConfig();
                c.macName = etName.getText().toString().trim();
                c.macMac = etMac.getText().toString().trim();
                c.broadcast = etAdv.getText().toString().trim();
                c.noName = cbNoName.isChecked();
                c.onlyLike = cbOnlyLike.isChecked();
                c.onlyBLE = cbOnlyBle.isChecked();
                c.rssi = seek.getProgress() - 100;
                if (listener != null) listener.onFilterChanged(c);
                dismissAllowingStateLoss();
            }
        });
        v.findViewById(R.id.btnReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                etName.setText("");
                etMac.setText("");
                etAdv.setText("");
                cbNoName.setChecked(false);
                cbOnlyLike.setChecked(false);
                cbOnlyBle.setChecked(false);
                seek.setProgress(0);
                tvRssi.setText("-100 dBm");
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
