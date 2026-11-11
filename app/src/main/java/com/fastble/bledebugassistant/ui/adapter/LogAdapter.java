package com.fastble.bledebugassistant.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.model.LogItem;

/**
 * 日志适配器：直接从 LogRepository 按下标读取，不需要在适配器里复制一份数据，
 * 每秒上千行也不会因为列表复制而卡顿。
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    private final Context context;

    public LogAdapter(Context context) {
        this.context = context;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LogItem item = LogRepository.get().get(position);
        if (item == null) return;
        h.time.setText(item.time);
        h.content.setText(item.content);
        int color;
        switch (item.type) {
            case LogItem.TYPE_TX:
                color = R.color.log_tx;
                break;
            case LogItem.TYPE_RX:
                color = R.color.log_rx;
                break;
            case LogItem.TYPE_ERROR:
                color = R.color.log_error;
                break;
            default:
                color = R.color.log_info;
                break;
        }
        h.content.setTextColor(ContextCompat.getColor(context, color));
    }

    @Override
    public int getItemCount() {
        return LogRepository.get().size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView time, content;

        VH(View v) {
            super(v);
            time = v.findViewById(R.id.tvTime);
            content = v.findViewById(R.id.tvContent);
        }
    }
}
