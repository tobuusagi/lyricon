/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 */

package com.android.systemui;

public class Dependency {
    public static Dependency sDependency;
    public Object mDarkIconDispatcher;

    public Object getDependencyInner(Object key) {
        return mDarkIconDispatcher;
    }
}
