package com.fastble.bledebugassistant.core;

import android.os.SystemClock;

/**
 * 实时速度统计（滑动窗口）。
 * 只在 UI 线程/单个消费线程访问，方法内部仍做了同步保护。
 */
public class RateMeter {

    private final long windowMs;
    private long windowStart;
    private long windowBytes;
    private long totalBytes;
    private double lastBps;

    public RateMeter(long windowMs) {
        this.windowMs = windowMs;
        this.windowStart = SystemClock.elapsedRealtime();
    }

    public synchronized void add(long bytes) {
        windowBytes += bytes;
        totalBytes += bytes;
    }

    /**
     * 每秒调用若干次，返回最近一次计算出的速率（byte/s）。未到窗口时返回上一次的值。
     */
    public synchronized double tick() {
        long now = SystemClock.elapsedRealtime();
        long dt = now - windowStart;
        if (dt >= windowMs) {
            lastBps = windowBytes * 1000.0 / dt;
            windowStart = now;
            windowBytes = 0;
        }
        return lastBps;
    }

    public synchronized double getBps() {
        return lastBps;
    }

    public synchronized long getTotalBytes() {
        return totalBytes;
    }

    public synchronized void reset() {
        windowStart = SystemClock.elapsedRealtime();
        windowBytes = 0;
        totalBytes = 0;
        lastBps = 0;
    }
}
