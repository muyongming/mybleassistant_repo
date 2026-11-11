package com.fastble.bledebugassistant.core;

import com.fastble.bledebugassistant.util.HexUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 接收引擎 —— 保证高速通知（notify/indicate）时不丢一个包。
 *
 * 关键设计（与原版“回调里直接排队 + Handler 刷新”异曲同工，但更稳）：
 * 1. onCharacteristicChanged 回调线程里只做 queue.offer(bytes)，不格式化、不写文件、不碰 UI。
 *    回调线程绝不阻塞，协议栈才不会因应用层慢而丢通知包。
 * 2. 单消费线程批量取数据（最多 512 包或 20ms），一次格式化/一次回调 UI，
 *    UI 消息量下降 1~2 个数量级，千包/秒也不会 ANR。
 * 3. “保存到文件”直接写原始字节（256KB 缓冲），与 HEX 展示完全解耦。
 * 4. 总字节数与实时速度独立统计，不因 UI 限流而失真。
 */
public class BleRxEngine {

    public interface Listener {
        /** @param text 本批次可展示文本（已按 HEX/ASCII 格式化） */
        void onBatch(String text, long totalBytes, double bytesPerSec);
    }

    private static final int MAX_DRAIN = 512;
    private static final int MAX_TEXT_CHARS = 4096;

    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final RateMeter meter = new RateMeter(500);
    private final StringBuilder sb = new StringBuilder(MAX_TEXT_CHARS + 1024);

    private volatile boolean running;
    private Thread consumer;

    private volatile boolean hex = true;
    private volatile boolean displayEnabled = true;
    private volatile BufferedOutputStream fileOut;
    private volatile File saveFile;

    private volatile Listener listener;

    public void start(Listener l) {
        this.listener = l;
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ble-rx");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        consumer = t;
        t.start();
    }

    public void stop() {
        running = false;
        Thread t = consumer;
        if (t != null) t.interrupt();
        consumer = null;
        queue.clear();
        stopSave();
    }

    public boolean isRunning() {
        return running;
    }

    public void setHex(boolean hex) {
        this.hex = hex;
    }

    /** false = 不生成展示文本（只统计/写文件），吞吐最高 */
    public void setDisplayEnabled(boolean enabled) {
        this.displayEnabled = enabled;
    }

    public long getTotalBytes() {
        return meter.getTotalBytes();
    }

    public double getBps() {
        return meter.tick();
    }

    public void resetCounter() {
        meter.reset();
    }

    /** 从 BLE 回调线程调用，只入队 */
    public void put(byte[] data) {
        if (data == null || data.length == 0) return;
        if (!running) {
            // 未启动也不丢：自动启动仅统计
            if (consumer == null) start(null);
        }
        queue.offer(data);
    }

    // ------------------------------------------------------------------ 保存文件

    public boolean startSave(File file) {
        stopSave();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            fileOut = new BufferedOutputStream(new FileOutputStream(file, false), 256 * 1024);
            saveFile = file;
            return true;
        } catch (Exception e) {
            fileOut = null;
            saveFile = null;
            return false;
        }
    }

    public void stopSave() {
        BufferedOutputStream out = fileOut;
        fileOut = null;
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (Exception ignore) {
            }
        }
    }

    public File getSaveFile() {
        return saveFile;
    }

    // ------------------------------------------------------------------ 消费线程

    private void loop() {
        List<byte[]> batch = new ArrayList<>(MAX_DRAIN);
        while (running) {
            byte[] first;
            try {
                first = queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (first == null) continue;

            batch.clear();
            batch.add(first);
            queue.drainTo(batch, MAX_DRAIN - 1);

            boolean dis = displayEnabled;
            boolean useHex = hex;
            sb.setLength(0);
            BufferedOutputStream out = fileOut;
            boolean wrote = false;

            for (int i = 0; i < batch.size(); i++) {
                byte[] d = batch.get(i);
                meter.add(d.length);
                if (out != null) {
                    try {
                        out.write(d);
                        wrote = true;
                    } catch (Exception ignore) {
                    }
                }
                if (dis) {
                    if (useHex) {
                        appendHex(sb, d);
                        sb.append('\n');
                    } else {
                        sb.append(HexUtil.toAscii(d));
                    }
                    if (sb.length() >= MAX_TEXT_CHARS) {
                        emit(sb.toString());
                        sb.setLength(0);
                    }
                }
            }
            if (wrote && out != null) {
                try {
                    out.flush();
                } catch (Exception ignore) {
                }
            }
            if (sb.length() > 0) {
                emit(sb.toString());
                sb.setLength(0);
            }
        }
    }

    private void emit(String text) {
        Listener l = listener;
        if (l != null) {
            l.onBatch(text, meter.getTotalBytes(), meter.tick());
        }
    }

    private static void appendHex(StringBuilder sb, byte[] d) {
        final char[] HEX = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            if (i != d.length - 1) sb.append(' ');
        }
    }
}
