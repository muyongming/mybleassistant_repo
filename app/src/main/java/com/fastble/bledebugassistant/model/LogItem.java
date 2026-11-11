package com.fastble.bledebugassistant.model;

/**
 * 一条日志（对应原版 LogJson）。
 */
public class LogItem {

    public static final int TYPE_TX = 0;      // 发送
    public static final int TYPE_RX = 1;      // 接收
    public static final int TYPE_INFO = 2;    // 系统/状态
    public static final int TYPE_ERROR = 3;   // 错误

    public final int type;
    public final String time;
    public final String content;

    public LogItem(int type, String time, String content) {
        this.type = type;
        this.time = time;
        this.content = content;
    }

    @Override
    public String toString() {
        return time + "-->" + content;
    }
}
