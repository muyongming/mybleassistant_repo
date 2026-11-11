package com.fastble.bledebugassistant.ui.adapter;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.util.HexUtil;
import com.fastble.bledebugassistant.util.UuidUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务 / 特征值 / 描述符 三级展示（对应原版 ExpandListAdapter）。
 */
public class ServiceTreeAdapter extends BaseExpandableListAdapter {

    public interface Listener {
        void onRead(BluetoothGattCharacteristic characteristic);

        void onWrite(BluetoothGattCharacteristic characteristic);
    }

    private final Context context;
    private final Listener listener;
    private final List<BluetoothGattService> groups = new ArrayList<>();
    private final List<List<BluetoothGattCharacteristic>> children = new ArrayList<>();

    public ServiceTreeAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setServices(List<BluetoothGattService> services) {
        groups.clear();
        children.clear();
        if (services != null) {
            for (BluetoothGattService s : services) {
                groups.add(s);
                List<BluetoothGattCharacteristic> list = new ArrayList<>();
                if (s.getCharacteristics() != null) list.addAll(s.getCharacteristics());
                children.add(list);
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getGroupCount() {
        return groups.size();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        return children.get(groupPosition).size();
    }

    @Override
    public Object getGroup(int groupPosition) {
        return groups.get(groupPosition);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        return children.get(groupPosition).get(childPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return groupPosition * 1000L + childPosition;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_service_group, parent, false);
        }
        BluetoothGattService s = groups.get(groupPosition);
        TextView name = convertView.findViewById(R.id.tvServiceName);
        TextView uuid = convertView.findViewById(R.id.tvServiceUuid);
        name.setText("Service: " + UuidUtil.describeService(s) + " (" + children.get(groupPosition).size() + ")");
        uuid.setText("UUID: " + UuidUtil.shortUuid(s.getUuid()) + "    Type: " + s.getType());
        return convertView;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                             View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_characteristic, parent, false);
        }
        final BluetoothGattCharacteristic ch = children.get(groupPosition).get(childPosition);

        TextView name = convertView.findViewById(R.id.tvCharName);
        TextView uuid = convertView.findViewById(R.id.tvCharUuid);
        TextView props = convertView.findViewById(R.id.tvCharProps);
        View read = convertView.findViewById(R.id.ivRead);
        View write = convertView.findViewById(R.id.ivWrite);
        LinearLayout descContainer = convertView.findViewById(R.id.descriptorContainer);

        name.setText(UuidUtil.nameOf(ch.getUuid()));
        uuid.setText("UUID: " + UuidUtil.shortUuid(ch.getUuid()));
        props.setText("Properties: " + UuidUtil.describeProperties(ch));

        boolean canRead = UuidUtil.canRead(ch);
        boolean canWrite = UuidUtil.canWrite(ch);
        read.setVisibility(canRead ? View.VISIBLE : View.INVISIBLE);
        write.setVisibility(canWrite ? View.VISIBLE : View.INVISIBLE);
        read.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onRead(ch);
            }
        });
        write.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onWrite(ch);
            }
        });

        descContainer.removeAllViews();
        List<BluetoothGattDescriptor> descriptors = ch.getDescriptors();
        if (descriptors != null && !descriptors.isEmpty()) {
            for (BluetoothGattDescriptor d : descriptors) {
                View row = LayoutInflater.from(context).inflate(R.layout.item_descriptor, descContainer, false);
                TextView desc = row.findViewById(R.id.tvDesc);
                byte[] value = d.getValue();
                String v = value == null || value.length == 0 ? "" : (" = " + HexUtil.toHexString(value));
                desc.setText("  └ " + UuidUtil.describeDescriptor(d) + "  " + UuidUtil.shortUuid(d.getUuid()) + v);
                row.findViewById(R.id.tvDescRead).setVisibility(View.GONE);
                row.findViewById(R.id.tvDescWrite).setVisibility(View.GONE);
                descContainer.addView(row);
            }
        }
        return convertView;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }
}
