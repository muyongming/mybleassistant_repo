package com.fastble.bledebugassistant.ui.fragment;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ExpandableListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.BleController;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.ui.adapter.ServiceTreeAdapter;
import com.fastble.bledebugassistant.ui.dialog.ReadDialogFragment;
import com.fastble.bledebugassistant.ui.dialog.WriteDialogFragment;

import java.util.List;

/**
 * 服务列表页（对应原版 MainFragment）：服务→特征值→描述符树形展示。
 */
public class MainFragment extends Fragment {

    private ExpandableListView expandList;
    private TextView tvMtuInfo;
    private ServiceTreeAdapter adapter;

    public static MainFragment newInstance() {
        return new MainFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        expandList = view.findViewById(R.id.expandList);
        tvMtuInfo = view.findViewById(R.id.tvMtuInfo);

        adapter = new ServiceTreeAdapter(requireContext(), new ServiceTreeAdapter.Listener() {
            @Override
            public void onRead(BluetoothGattCharacteristic characteristic) {
                ReadDialogFragment.show(getParentFragmentManager(), characteristic);
            }

            @Override
            public void onWrite(BluetoothGattCharacteristic characteristic) {
                WriteDialogFragment.show(getParentFragmentManager(), characteristic);
            }
        });
        expandList.setAdapter(adapter);
        refresh();
    }

    /** 连接建立 / MTU 变化后刷新 */
    public void refresh() {
        if (!isAdded()) return;
        List<BluetoothGattService> services = BleController.get().getServices();
        adapter.setServices(services);
        for (int i = 0; i < adapter.getGroupCount(); i++) {
            expandList.expandGroup(i);
        }
        updateMtuInfo();
        if (services == null || services.isEmpty()) {
            LogRepository.get().info("未获取到服务，请确认已连接设备");
        }
    }

    public void updateMtuInfo() {
        if (tvMtuInfo == null) return;
        int mtu = BleController.get().getMtu();
        tvMtuInfo.setText("MTU: " + mtu + "    单包负载: " + (mtu - 3) + " Byte");
    }
}
