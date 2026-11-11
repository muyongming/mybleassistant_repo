package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;

/**
 * 设置偏好 PHY 弹窗（对应原版 SetPhyDialogFragment）。
 * Android 原生 setPreferredPhy(txPhy, rxPhy, options) 每项只能选一个 PHY，
 * 这里用 CheckBox 组合，最终取优先级：2M > Coded > 1M。
 */
public class PhyDialogFragment extends DialogFragment {

    public interface Callback {
        void onPhySelected(int txPhy, int rxPhy, int options);
    }

    private static Callback pendingCallback;

    public static void show(FragmentManager fm, Callback callback) {
        pendingCallback = callback;
        new PhyDialogFragment().show(fm, "phy");
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
        View v = inflater.inflate(R.layout.dialog_phy, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final CheckBox tx1 = v.findViewById(R.id.cbTx1m);
        final CheckBox tx2 = v.findViewById(R.id.cbTx2m);
        final CheckBox txc = v.findViewById(R.id.cbTxCoded);
        final CheckBox rx1 = v.findViewById(R.id.cbRx1m);
        final CheckBox rx2 = v.findViewById(R.id.cbRx2m);
        final CheckBox rxc = v.findViewById(R.id.cbRxCoded);
        final RadioGroup rgCoded = v.findViewById(R.id.radioGroupCoded);
        final RadioButton rbS2 = v.findViewById(R.id.rbS2);
        final RadioButton rbS8 = v.findViewById(R.id.rbS8);

        tx2.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                rgCoded.setVisibility((tx2.isChecked() || rx2.isChecked()) ? View.GONE : View.VISIBLE);
            }
        });
        rx2.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                rgCoded.setVisibility((tx2.isChecked() || rx2.isChecked()) ? View.GONE : View.VISIBLE);
            }
        });

        v.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                dismissAllowingStateLoss();
            }
        });
        v.findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                int tx = tx2.isChecked() ? BluetoothDevice.PHY_LE_2M
                        : (txc.isChecked() ? BluetoothDevice.PHY_LE_CODED : BluetoothDevice.PHY_LE_1M);
                int rx = rx2.isChecked() ? BluetoothDevice.PHY_LE_2M
                        : (rxc.isChecked() ? BluetoothDevice.PHY_LE_CODED : BluetoothDevice.PHY_LE_1M);
                int options = rbS2.isChecked() ? BluetoothDevice.PHY_OPTION_S2
                        : (rbS8.isChecked() ? BluetoothDevice.PHY_OPTION_S8
                        : BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                if (pendingCallback != null) pendingCallback.onPhySelected(tx, rx, options);
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
