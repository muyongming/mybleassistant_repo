package com.fastble.bledebugassistant.util;

import android.annotation.SuppressLint;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class TimeUtil {

    private static final ThreadLocal<SimpleDateFormat> HMS =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                }
            };

    private static final ThreadLocal<SimpleDateFormat> FILE_NAME =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                }
            };

    private TimeUtil() {
    }

    public static String now() {
        return HMS.get().format(new Date());
    }

    @SuppressLint("SimpleDateFormat")
    public static String fileNameStamp() {
        return FILE_NAME.get().format(new Date());
    }
}
