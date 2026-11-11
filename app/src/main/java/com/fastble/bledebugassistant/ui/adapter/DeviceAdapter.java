package com.fastble.bledebugassistant.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.model.Beans;
import com.fastble.bledebugassistant.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    public interface Listener {
        void onConnect(Beans.DeviceItem item);

        void onStarClick(Beans.DeviceItem item);

        void onShowAdv(Beans.DeviceItem item);
    }

    private final Context context;
    private final Listener listener;
    private final List<Beans.DeviceItem> data = new ArrayList<>();

    public DeviceAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public List<Beans.DeviceItem> getData() {
        return data;
    }

    public void clear() {
        data.clear();
        notifyDataSetChanged();
    }

    /** 同一个 MAC 只保留一行，实时刷新 RSSI */
    public void upsert(Beans.DeviceItem item) {
        if (item == null || item.mac == null) return;
        for (int i = 0; i < data.size(); i++) {
            if (item.mac.equalsIgnoreCase(data.get(i).mac)) {
                Beans.DeviceItem old = data.get(i);
                old.rssi = item.rssi;
                old.scanRecord = item.scanRecord;
                old.lastSeen = item.lastSeen;
                if (item.name != null && item.name.length() > 0) old.name = item.name;
                old.device = item.device != null ? item.device : old.device;
                notifyItemChanged(i);
                return;
            }
        }
        data.add(item);
        notifyItemInserted(data.size() - 1);
    }

    public void removeByMac(String mac) {
        if (mac == null) return;
        for (int i = 0; i < data.size(); i++) {
            if (mac.equalsIgnoreCase(data.get(i).mac)) {
                data.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public boolean contains(String mac) {
        if (mac == null) return false;
        for (Beans.DeviceItem d : data) {
            if (mac.equalsIgnoreCase(d.mac)) return true;
        }
        return false;
    }

    /** 根据名称/MAC 关键字做一次本地过滤，返回过滤后数量 */
    public int applyKeyword(String keyword) {
        // 注意：这里不改动数据源，只返回匹配数量，真正的过滤在 Activity 中做
        if (keyword == null || keyword.length() == 0) return data.size();
        int n = 0;
        String k = keyword.toLowerCase();
        for (Beans.DeviceItem d : data) {
            String name = d.displayName().toLowerCase();
            String mac = d.mac == null ? "" : d.mac.toLowerCase();
            if (name.contains(k) || mac.contains(k)) n++;
        }
        return n;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Beans.DeviceItem item = data.get(position);
        h.name.setText(item.displayName());
        h.mac.setText(item.mac);
        h.rssi.setText(item.rssi + " dBm");
        h.star.setImageResource(item.favourite ? R.drawable.ic_star_on : R.drawable.ic_star_off);

        StringBuilder extra = new StringBuilder();
        if (item.bonded) extra.append("已配对  ");
        if (item.scanRecord != null && item.scanRecord.length > 0) {
            extra.append("ADV ").append(item.scanRecord.length).append(" B");
        }
        h.extra.setText(extra.toString());

        h.star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onStarClick(item);
            }
        });
        h.connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onConnect(item);
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) listener.onShowAdv(item);
                return true;
            }
        });
        h.extra.setVisibility(extra.length() == 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView star;
        final TextView name, mac, extra, rssi;
        final View connect;

        VH(View v) {
            super(v);
            star = v.findViewById(R.id.ivStar);
            name = v.findViewById(R.id.tvName);
            mac = v.findViewById(R.id.tvMac);
            extra = v.findViewById(R.id.tvExtra);
            rssi = v.findViewById(R.id.tvRssi);
            connect = v.findViewById(R.id.btnConnect);
        }
    }
}
