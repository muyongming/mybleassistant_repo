package com.fastble.bledebugassistant.core;

import android.bluetooth.BluetoothGattCharacteristic;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 发送引擎 —— 保证“连续快速发送高速数据包”时一个字节都不丢。
 *
 * 关键设计：
 * 1. 分包：包长 = MTU - 3（ATT 头 3 字节），MTU 变化后自动生效。
 * 2. 严格串行：同一个 GATT 通道同时只存在一个 write 操作。
 *    Android 的 BluetoothGatt 是单操作模型，若不等回调就发下一包，
 *    后续 writeCharacteristic() 会返回 false 或直接被协议栈丢掉 —— 这是绝大多数
 *    “高速收发丢包”的根本原因。这里用 CountDownLatch 等回调，发完一包才发下一包。
 * 3. 失败重传：写失败/超时重发【同一包】（不跳包），最多 3 次；
 *    仍失败则整个任务失败并上报，绝不静默丢数据。
 * 4. 间隔控制：用 elapsedRealtimeNanos 计算剩余时间，避免 Thread.sleep 抖动累积。
 * 5. 数据源可以是内存数据或文件流：文件发送时只有滑动窗口大小的内存占用，
 *    即使几个 G 的文件也不会 OOM。
 */
public class BleTxEngine {

    public interface Listener {
        /** @param totalBytes <=0 表示未知（循环发送） */
        void onStarted(long totalBytes);

        void onProgress(long sentBytes, long totalBytes, double bytesPerSec);

        void onFinished(boolean success, String message);
    }

    public static class Request {
        public byte[] data;
        public File file;
        public boolean loop;
        public int intervalMs;
        public int writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        public boolean randomLength;
        public int randomMin = 1;
        public int randomMax = 20;

        public static Request data(byte[] data, boolean loop, int intervalMs, int writeType) {
            Request r = new Request();
            r.data = data;
            r.loop = loop;
            r.intervalMs = intervalMs;
            r.writeType = writeType;
            return r;
        }

        public static Request file(File file, boolean loop, boolean randomLength,
                                   int randomMin, int randomMax, int intervalMs, int writeType) {
            Request r = new Request();
            r.file = file;
            r.loop = loop;
            r.randomLength = randomLength;
            r.randomMin = randomMin;
            r.randomMax = randomMax;
            r.intervalMs = intervalMs;
            r.writeType = writeType;
            return r;
        }
    }

    private static final int MAX_RETRY = 3;
    private static final int WRITE_TIMEOUT_MS = 2000;
    private static final long PROGRESS_INTERVAL_MS = 100;

    private final RateMeter meter = new RateMeter(500);
    private final Random random = new Random();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile long sentBytes;
    private volatile long totalBytes;
    private volatile String lastError;

    public boolean isRunning() {
        return running;
    }

    public long getSentBytes() {
        return sentBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public double getBps() {
        return meter.tick();
    }

    public String getLastError() {
        return lastError;
    }

    public void start(final BluetoothGattCharacteristic characteristic, final Request request,
                      final Listener listener) {
        if (running) return;
        running = true;
        sentBytes = 0;
        lastError = null;
        meter.reset();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                transfer(characteristic, request, listener);
            }
        }, "ble-tx");
        t.setPriority(Thread.MAX_PRIORITY);
        worker = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    // ------------------------------------------------------------------

    private void transfer(BluetoothGattCharacteristic ch, Request req, Listener l) {
        InputStream in = null;
        long fileLen = 0;
        try {
            if (ch == null) {
                finish(l, false, "characteristic is null");
                return;
            }
            if (req.file != null) {
                fileLen = req.file.length();
                in = openFile(req.file);
                totalBytes = fileLen;
            } else if (req.data != null) {
                in = new ByteArrayInputStream(req.data);
                totalBytes = req.data.length;
            } else {
                finish(l, false, "no data");
                return;
            }
            if (l != null) l.onStarted(req.loop ? -1 : totalBytes);

            byte[] window = new byte[1024];
            long sent = 0;
            long lastProgress = 0;

            while (running) {
                int payload = BleController.get().getPayloadSize();
                int want = payload;
                if (req.randomLength) {
                    int min = Math.max(1, Math.min(req.randomMin, req.randomMax));
                    int max = Math.max(min, Math.max(req.randomMin, req.randomMax));
                    want = min + random.nextInt(max - min + 1);
                }
                if (want > payload) want = payload;
                if (want > window.length) window = new byte[want];

                int n = readFully(in, window, want);
                if (n <= 0) {
                    // 数据源结束
                    if (req.loop) {
                        closeQuietly(in);
                        in = req.file != null ? openFile(req.file) : new ByteArrayInputStream(req.data);
                        continue;
                    }
                    break;
                }
                byte[] packet = new byte[n];
                System.arraycopy(window, 0, packet, 0, n);

                boolean ok = writePacketWithRetry(ch, packet, req.writeType);
                if (!ok) {
                    finish(l, false, lastError == null ? "write failed" : lastError);
                    return;
                }
                sent += n;
                sentBytes = sent;
                meter.add(n);

                long now = SystemClock.elapsedRealtime();
                if (l != null && (now - lastProgress >= PROGRESS_INTERVAL_MS)) {
                    lastProgress = now;
                    l.onProgress(sent, req.file != null ? fileLen : totalBytes, meter.tick());
                }
                if (req.intervalMs > 0) {
                    sleepPrecise(req.intervalMs);
                }
            }
            if (l != null) {
                l.onProgress(sent, req.file != null ? fileLen : totalBytes, meter.tick());
            }
            finish(l, running, running ? null : "cancelled");
        } catch (Throwable t) {
            finish(l, false, String.valueOf(t.getMessage()));
        } finally {
            closeQuietly(in);
            running = false;
            worker = null;
        }
    }

    private void finish(Listener l, boolean success, String message) {
        running = false;
        if (l != null) l.onFinished(success, message);
    }

    /**
     * 发一包（一包 = 一次 writeCharacteristic）。
     * 必须等到回调再返回，否则会导致下一包被协议栈丢弃。
     */
    private boolean writePacketWithRetry(final BluetoothGattCharacteristic ch, final byte[] packet,
                                         int writeType) {
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            if (!running) return false;
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = new boolean[]{false};
            final String[] err = new String[]{null};

            boolean accepted = BleController.get().write(ch, packet, writeType,
                    new BleController.WriteCallback() {
                        public void onOk() {
                            ok[0] = true;
                            latch.countDown();
                        }

                        public void onErr(String message) {
                            err[0] = message;
                            latch.countDown();
                        }
                    });
            if (!accepted) {
                lastError = err[0] == null ? "write not accepted" : err[0];
                if (!running) return false;
                sleepPrecise(8);
                continue;
            }
            try {
                boolean done = latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (done && ok[0]) return true;
                lastError = done ? (err[0] == null ? "write fail" : err[0]) : "write timeout";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!running) return false;
            // 重发同一包，不跳包
            sleepPrecise(10);
        }
        return false;
    }

    private static int readFully(InputStream in, byte[] buf, int want) throws Exception {
        int off = 0;
        while (off < want) {
            int r = in.read(buf, off, want - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static InputStream openFile(File f) throws Exception {
        return new BufferedInputStream(new FileInputStream(f), 64 * 1024);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    /** 精确到毫秒的间隔等待（不足 1ms 时用自旋，保证高速发包时不被系统调度放大） */
    private static void sleepPrecise(long ms) {
        long end = SystemClock.elapsedRealtimeNanos() + ms * 1000000L;
        while (true) {
            long remain = end - SystemClock.elapsedRealtimeNanos();
            if (remain <= 0) return;
            if (remain > 2000000L) {
                try {
                    Thread.sleep(remain / 2000000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.yield();
            }
        }
    }
}
