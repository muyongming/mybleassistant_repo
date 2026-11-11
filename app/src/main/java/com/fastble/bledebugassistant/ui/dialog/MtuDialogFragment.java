package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
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
import com.fastble.bledebugassistant.util.ToastUtil;

/**
 * 修改 MTU 弹窗（对应原版 SetMTUDialogFragment）。
 */
public class MtuDialogFragment extends DialogFragment {

    public interface Callback {
        void onMtuSelected(int mtu);
    }

    private static Callback pendingCallback;

    public static void show(FragmentManager fm, int currentMtu, Callback callback) {
        pendingCallback = callback;
        Bundle b = new Bundle();
        b.putInt("mtu", currentMtu);
        MtuDialogFragment f = new MtuDialogFragment();
        f.setArguments(b);
        f.show(fm, "mtu");
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
        View v = inflater.inflate(R.layout.dialog_mtu, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        int current = getArguments() == null ? 23 : getArguments().getInt("mtu", 23);
        TextView tvCurrent = v.findViewById(R.id.tvCurrentMtu);
        tvCurrent.setText("当前 MTU: " + current + "    单包负载: " + (current - 3) + " Byte");

        final EditText et = v.findViewById(R.id.etMtu);
        et.setText(String.valueOf(current));
        et.setSelection(et.getText().length());

        v.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                dismissAllowingStateLoss();
            }
        });
        v.findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                String s = et.getText() == null ? "" : et.getText().toString().trim();
                if (TextUtils.isEmpty(s)) {
                    ToastUtil.show(getContext(), getString(R.string.mtu_empty));
                    return;
                }
                int mtu;
                try {
                    mtu = Integer.parseInt(s);
                } catch (Exception e) {
                    ToastUtil.show(getContext(), getString(R.string.mtu_empty));
                    return;
                }
                if (mtu < 23) mtu = 23;
                if (mtu > 517) mtu = 517;
                if (pendingCallback != null) pendingCallback.onMtuSelected(mtu);
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
