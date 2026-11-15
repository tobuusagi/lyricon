/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.proify.lyricon.subscriber.internal.LyriconSubscriberImpl
import io.github.proify.lyricon.subscriber.internal.registration.CentralBootReceiver

/**
 * 订阅端测试桥接器：跨 Gradle 模块暴露测试所需的 Seam 与生命周期重置，
 * 避免破坏内部类的封装，同时保证单测真实调用生产逻辑。
 */
@VisibleForTesting
object SubscriberTestBridge {

    fun setBroadcastDispatcher(dispatcher: ((Context, String, Any) -> Unit)?) {
        LyriconSubscriberImpl.broadcastDispatcher = dispatcher?.let { d ->
            { ctx, action, binder -> d(ctx, action, binder) }
        }
    }

    fun setForceSupported(force: Boolean) {
        LyriconFactory.isForceSupported = force
    }

    fun resetBootReceiver() {
        CentralBootReceiver.resetForTesting()
    }

    fun isConnected(subscriber: LyriconSubscriber): Boolean {
        return (subscriber as? LyriconSubscriberImpl)?.status == SubscriberStatus.CONNECTED
    }
}
