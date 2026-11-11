package com.fastble.bledebugassistant.util;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 标准 GATT UUID / Properties 名称展示。
 */
public final class UuidUtil {

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("00001800", "Generic Access");
        NAMES.put("00001801", "Generic Attribute");
        NAMES.put("0000180a", "Device Information");
        NAMES.put("0000180f", "Battery Service");
        NAMES.put("00001812", "Human Interface Device");
        NAMES.put("0000180d", "Heart Rate");
        NAMES.put("0000ffe0", "WCH Serial");
        NAMES.put("0000ffe1", "WCH Serial Data");
        NAMES.put("00002a00", "Device Name");
        NAMES.put("00002a01", "Appearance");
        NAMES.put("00002a04", "Peripheral Preferred Connection Parameters");
        NAMES.put("00002a05", "Service Changed");
        NAMES.put("00002a19", "Battery Level");
        NAMES.put("00002a29", "Manufacturer Name String");
        NAMES.put("00002a24", "Model Number String");
        NAMES.put("00002a25", "Serial Number String");
        NAMES.put("00002a26", "Firmware Revision String");
        NAMES.put("00002a27", "Hardware Revision String");
        NAMES.put("00002a28", "Software Revision String");
        NAMES.put("00002902", "Client Characteristic Configuration");
        NAMES.put("00002901", "Characteristic User Description");
    }

    private UuidUtil() {
    }

    public static String shortUuid(UUID uuid) {
        String s = uuid.toString().toUpperCase(Locale.US);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB")) {
            return "0x" + s.substring(4, 8);
        }
        return s;
    }

    public static String nameOf(UUID uuid) {
        String s = uuid.toString().toLowerCase(Locale.US);
        if (s.length() >= 8) {
            String v = NAMES.get(s.substring(0, 8));
            if (v != null) return v;
        }
        return shortUuid(uuid);
    }

    public static String describeService(BluetoothGattService service) {
        return nameOf(service.getUuid());
    }

    /** 与原版一致的 Properties 位标志展示 */
    public static String describeProperties(BluetoothGattCharacteristic ch) {
        StringBuilder sb = new StringBuilder();
        int p = ch.getProperties();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("Read ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("Write ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("WriteNoRsp ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("Notify ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("Indicate ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) sb.append("Broadcast ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) sb.append("SignedWrite ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) sb.append("ExtProps ");
        return sb.toString().trim();
    }

    public static boolean canRead(BluetoothGattCharacteristic ch) {
        return (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
    }

    public static boolean canWrite(BluetoothGattCharacteristic ch) {
        return (ch.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    public static boolean canNotify(BluetoothGattCharacteristic ch) {
        return (ch.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    public static boolean canIndicate(BluetoothGattCharacteristic ch) {
        return (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                && (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
    }

    public static String describeDescriptor(BluetoothGattDescriptor d) {
        return nameOf(d.getUuid());
    }
}
