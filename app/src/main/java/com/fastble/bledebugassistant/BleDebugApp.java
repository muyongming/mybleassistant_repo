package com.fastble.bledebugassistant;

import android.app.Application;

import com.fastble.bledebugassistant.core.BleController;

public class BleDebugApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // FastBle 初始化（全局单例）
        BleController.get().init(this);
    }
}
