/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.plugins.DarkIconDispatcher;
import java.util.ArrayList;

public class DarkIconDispatcherImpl implements DarkIconDispatcher {
    public int mIconTint = 0xFF123456;
    public float mDarkIntensity = 0.8f;
    public int mContrastTint = 0xFF000000;

    public DarkIconDispatcherImpl() {}

    public DarkIconDispatcherImpl(int iconTint, float darkIntensity) {
        this.mIconTint = iconTint;
        this.mDarkIntensity = darkIntensity;
    }

    public void applyDarkIntensity(float darkIntensity) {
        this.mDarkIntensity = darkIntensity;
        this.mIconTint = (darkIntensity > 0.5f) ? 0xFF000000 : 0xFFFFFFFF;
    }

    public void setIconsDarkArea(ArrayList areas) {
    }

    @Override
    public void addDarkReceiver(DarkReceiver receiver) {}

    @Override
    public void removeDarkReceiver(DarkReceiver receiver) {}

    @Override
    public void applyDark(DarkReceiver receiver) {}
}
