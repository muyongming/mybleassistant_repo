package com.fastble.bledebugassistant.core;

import android.os.Handler;
import android.os.Looper;

import com.fastble.bledebugassistant.model.LogItem;
import com.fastble.bledebugassistant.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 全局日志仓库（替代原版 LogFragment 里的 ConcurrentLinkedQueue + Handler 方案）。
 *
 * 设计要点（高速收发不卡顿、不丢日志）：
 * 1. add() 可从任意线程（包括 BLE 回调线程）调用，只做入队，不做格式化/UI。
 * 2. 主线程一个 60ms 的节拍统一批量刷 UI（notifyItemRangeInserted），
 *    避免高速数据下一条数据一次 notify 导致的卡死。
 * 3. 列表最多保留 MAX_KEEP 条（只限制“显示缓存”，不影响文件落盘），
 *    超出时从头部丢弃并通知适配器。
 */
public class LogRepository {

    public interface Listener {
        /** @param removedFromHead 因超出上限而丢弃的条数 */
        void onLogChanged(int removedFromHead);
    }

    // 只限制“屏幕上的日志条数”，防止高速收发长时间运行占满内存；
    // 导出文件时只导出当前缓存内容。
    private static final int MAX_KEEP = 5000;
    private static final long FLUSH_INTERVAL_MS = 60;

    private static volatile LogRepository sInstance;

    private final List<LogItem> items = new ArrayList<>();
    private final LinkedBlockingQueue<LogItem> pending = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean paused = false;
    private boolean flushScheduled = false;

    public static LogRepository get() {
        if (sInstance == null) {
            synchronized (LogRepository.class) {
                if (sInstance == null) sInstance = new LogRepository();
            }
        }
        return sInstance;
    }

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            if (pending.isEmpty()) return;
            // 一次性取空（限制单次数量，避免长任务）
            List<LogItem> drain = new ArrayList<>(Math.min(pending.size(), 4000));
            pending.drainTo(drain, 4000);
            if (drain.isEmpty()) return;
            synchronized (items) {
                items.addAll(drain);
            }
            int removed = 0;
            synchronized (items) {
                int over = items.size() - MAX_KEEP;
                if (over > 0) {
                    items.subList(0, over).clear();
                    removed = over;
                }
            }
            for (Listener l : listeners) {
                try {
                    l.onLogChanged(removed);
                } catch (Throwable ignore) {
                }
            }
            scheduleFlush();
        }
    };

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        main.postDelayed(flushTask, FLUSH_INTERVAL_MS);
    }

    public void add(int type, String content) {
        addRaw(new LogItem(type, TimeUtil.now(), content));
    }

    public void addRaw(LogItem item) {
        if (item == null) return;
        if (paused && (item.type == LogItem.TYPE_RX || item.type == LogItem.TYPE_TX)) {
            return;
        }
        pending.offer(item);
        scheduleFlush();
    }

    public void info(String content) {
        add(LogItem.TYPE_INFO, content);
    }

    public void error(String content) {
        add(LogItem.TYPE_ERROR, content);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void clear() {
        pending.clear();
        int n;
        synchronized (items) {
            n = items.size();
            items.clear();
        }
        for (Listener l : listeners) {
            try {
                l.onLogChanged(n);
            } catch (Throwable ignore) {
            }
        }
    }

    public List<LogItem> snapshot() {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    public int size() {
        synchronized (items) {
            return items.size();
        }
    }

    public LogItem get(int index) {
        synchronized (items) {
            if (index < 0 || index >= items.size()) return null;
            return items.get(index);
        }
    }

    public String dump() {
        List<LogItem> list = snapshot();
        StringBuilder sb = new StringBuilder(list.size() * 40);
        for (LogItem it : list) {
            sb.append(it.toString()).append("\r\n");
        }
        return sb.toString();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }
}
