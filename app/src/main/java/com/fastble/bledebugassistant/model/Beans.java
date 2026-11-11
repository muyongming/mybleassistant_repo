package com.fastble.bledebugassistant.model;

import android.bluetooth.BluetoothDevice;

import java.io.Serializable;

/**
 * 轻量数据对象集合（对应原版的 FilterJson / WriteJson / ReadJson / DeviceItem）。
 */
public final class Beans {

    private Beans() {
    }

    /** 扫描列表中的一台设备 */
    public static class DeviceItem {
        public String name;
        public String mac;
        public int rssi;
        public byte[] scanRecord;
        public long lastSeen;
        public boolean favourite;
        public boolean bonded;
        public BluetoothDevice device;   // createBond 需要

        public DeviceItem() {
        }

        public String displayName() {
            return (name == null || name.length() == 0) ? "N/A" : name;
        }
    }

    /** 扫描过滤条件（对应原版 FilterJson） */
    public static class FilterConfig implements Serializable {
        public String macName = "";        // 名称关键字
        public String macMac = "";         // MAC 关键字
        public String broadcast = "";      // 广播包 HEX 要求包含
        public boolean noName = false;     // 只显示无名称
        public boolean onlyLike = false;   // 只显示收藏
        public boolean onlyBLE = false;    // 只显示 LE-only
        public int rssi = -100;            // RSSI 阈值

        public int minRssi() {
            return rssi;
        }
    }

    /** 发送配置（对应原版 WriteJson） */
    public static class WriteConfig implements Serializable {
        public static final int MODE_SINGLE = 0;
        public static final int MODE_TIMED = 1;
        public static final int MODE_CYCLIC = 2;
        public static final int MODE_FILE = 3;

        public int mode = MODE_SINGLE;
        public boolean hex = true;             // 发送内容是否 HEX
        public int writeType = 2;              // 1=DEFAULT, 2=NO_RESPONSE（同 Android 常量）
        public int intervalMs = 10;            // 发送间隔
        public String content = "";            // 发送内容
        public String filePath = "";           // 文件路径
        public boolean randomLength = false;   // 随机长度
        public int randomMin = 1;
        public int randomMax = 20;
    }

    /** 接收配置（对应原版 ReadJson） */
    public static class ReadConfig implements Serializable {
        public boolean hex = true;              // 接收显示 HEX
        public boolean saveToFile = false;      // 保存至文件
        public String filePath = "";
    }
}
