package com.fastble.bledebugassistant.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public final class ToastUtil {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Toast last;

    private ToastUtil() {
    }

    public static void show(final Context context, final String msg) {
        if (context == null || msg == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (last != null) last.cancel();
                    last = Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT);
                    last.show();
                } catch (Throwable ignore) {
                }
            }
        });
    }
}
