/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package com.android.systemui.plugins;

public interface DarkIconDispatcher {
    interface DarkReceiver {
        void onDarkChanged(java.util.ArrayList<android.graphics.Rect> areas, float darkIntensity, int tint);
    }
    void addDarkReceiver(DarkReceiver receiver);
    void removeDarkReceiver(DarkReceiver receiver);
    void applyDark(DarkReceiver receiver);
}
