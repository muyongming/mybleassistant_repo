package com.fastble.bledebugassistant.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.model.Beans;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.ToastUtil;

/**
 * 广播包详情（对应原版 AdvDetailDialogFragment）：按 AD Structure 解析广播数据。
 */
public class AdvDetailDialogFragment extends DialogFragment {

    private static final String ARG_NAME = "name";
    private static final String ARG_MAC = "mac";
    private static final String ARG_RSSI = "rssi";
    private static final String ARG_DATA = "data";

    public static void show(FragmentManager fm, Beans.DeviceItem item) {
        if (item == null) return;
        Bundle b = new Bundle();
        b.putString(ARG_NAME, item.displayName());
        b.putString(ARG_MAC, item.mac);
        b.putInt(ARG_RSSI, item.rssi);
        b.putByteArray(ARG_DATA, item.scanRecord);
        AdvDetailDialogFragment f = new AdvDetailDialogFragment();
        f.setArguments(b);
        f.show(fm, "adv");
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
        View v = inflater.inflate(R.layout.dialog_adv_detail, container, false);
        Dialog d = getDialog();
        if (d != null && d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        Bundle args = getArguments();
        String name = args == null ? "N/A" : args.getString(ARG_NAME, "N/A");
        String mac = args == null ? "" : args.getString(ARG_MAC, "");
        int rssi = args == null ? 0 : args.getInt(ARG_RSSI, 0);
        byte[] data = args == null ? null : args.getByteArray(ARG_DATA);

        TextView tvInfo = v.findViewById(R.id.tvDeviceInfo);
        tvInfo.setText("名称: " + name + "\n地址: " + mac + "\nRSSI: " + rssi + " dBm\n长度: "
                + (data == null ? 0 : data.length) + " Byte");

        final String text = decode(data);
        TextView tvAdv = v.findViewById(R.id.tvAdv);
        tvAdv.setText(text);

        v.findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                try {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("adv", text));
                        ToastUtil.show(getContext(), getString(R.string.copy_ok));
                    }
                } catch (Throwable t) {
                    ToastUtil.show(getContext(), getString(R.string.copy_fail));
                }
            }
        });
        v.findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v1) {
                dismissAllowingStateLoss();
            }
        });
        return v;
    }

    private static String decode(byte[] data) {
        if (data == null || data.length == 0) return "无广播数据";
        StringBuilder sb = new StringBuilder();
        sb.append("原始数据:\n").append(HexUtil.toHexString(data)).append("\n\nAD Structures:\n");
        int i = 0;
        while (i < data.length) {
            int len = data[i] & 0xFF;
            if (len == 0) {
                sb.append(String.format("  [%02d] 结束标记\n", i));
                break;
            }
            if (i + len >= data.length) {
                sb.append(String.format("  [%02d] 长度越界，剩余 %d 字节\n", i, data.length - i));
                break;
            }
            int type = data[i + 1] & 0xFF;
            byte[] payload = new byte[len - 1];
            System.arraycopy(data, i + 2, payload, 0, len - 1);
            sb.append(String.format("  [%02d] len=%d type=0x%02X %s\n", i, len, type, typeName(type)));
            sb.append("       " ).append(HexUtil.toHexString(payload)).append("\n");
            if (type == 0x09 || type == 0x08) {
                sb.append("       ASCII: ").append(new String(payload)).append("\n");
            }
            i += len + 1;
        }
        return sb.toString();
    }

    private static String typeName(int type) {
        switch (type) {
            case 0x01:
                return "Flags";
            case 0x02:
            case 0x03:
                return "16-bit UUIDs";
            case 0x06:
            case 0x07:
                return "128-bit UUIDs";
            case 0x08:
                return "Shortened Local Name";
            case 0x09:
                return "Complete Local Name";
            case 0x0A:
                return "TX Power Level";
            case 0x0D:
                return "Class of Device";
            case 0x16:
                return "Service Data 16-bit";
            case 0xFF:
                return "Manufacturer Specific";
            default:
                return "";
        }
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
