package com.fastble.bledebugassistant.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fastble.bledebugassistant.R;
import com.fastble.bledebugassistant.core.LogRepository;
import com.fastble.bledebugassistant.ui.adapter.LogAdapter;
import com.fastble.bledebugassistant.ui.dialog.SaveFileDialogFragment;

/**
 * 日志页（对应原版 LogFragment）：暂停记录 / 清空 / 复制 / 导出。
 * 日志先入 LogRepository 队列，由 60ms 节拍批量插入列表，
 * 因此在高速收发时既不会丢日志也不会 ANR。
 */
public class LogFragment extends Fragment implements LogRepository.Listener {

    private RecyclerView recycler;
    private LogAdapter adapter;
    private Button btnPause;
    private int lastCount = 0;

    public static LogFragment newInstance() {
        return new LogFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        recycler = view.findViewById(R.id.recyclerLog);
        btnPause = view.findViewById(R.id.btnPause);

        final LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        recycler.setLayoutManager(lm);
        adapter = new LogAdapter(requireContext());
        recycler.setAdapter(adapter);
        lastCount = LogRepository.get().size();
        scrollToBottom();

        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean paused = !LogRepository.get().isPaused();
                LogRepository.get().setPaused(paused);
                btnPause.setText(paused ? R.string.log_resume : R.string.log_pause);
            }
        });
        view.findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogRepository.get().clear();
            }
        });
        view.findViewById(R.id.btnCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard();
            }
        });
        view.findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SaveFileDialogFragment.show(getParentFragmentManager(), "log", new SaveFileDialogFragment.Callback() {
                    @Override
                    public void onFileSelected(java.io.File file) {
                        boolean ok = SaveFileDialogFragment.writeText(file, LogRepository.get().dump());
                        Toast.makeText(requireContext(), ok ? "保存成功\n" + file.getAbsolutePath() : "保存失败",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        LogRepository.get().addListener(this);
    }

    @Override
    public void onPause() {
        LogRepository.get().removeListener(this);
        super.onPause();
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) {
            recycler.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void copyToClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("log", LogRepository.get().dump()));
            Toast.makeText(requireContext(), R.string.copy_ok, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.copy_fail, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLogChanged(int removedFromHead) {
        if (!isAdded()) return;
        int count = LogRepository.get().size();
        boolean atBottom = true;
        LinearLayoutManager lm = (LinearLayoutManager) recycler.getLayoutManager();
        if (lm != null) {
            int last = lm.findLastVisibleItemPosition();
            atBottom = last >= lastCount - 2;
        }
        if (removedFromHead > 0) {
            adapter.notifyDataSetChanged();
        } else if (count > lastCount) {
            adapter.notifyItemRangeInserted(lastCount, count - lastCount);
        }
        lastCount = count;
        if (atBottom) scrollToBottom();
    }
}
