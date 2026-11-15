/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.github.proify.lyricon.provider.internal.LyriconProviderImpl
import io.github.proify.lyricon.provider.internal.registration.CentralBootReceiver
import io.github.proify.lyricon.provider.internal.registration.RegistrationCoordinator

/**
 * 提供端测试桥接器：跨 Gradle 模块暴露测试所需的 Seam 与生命周期重置，
 * 避免破坏内部类的封装，同时保证单测真实调用生产逻辑。
 */
@VisibleForTesting
object ProviderTestBridge {

    fun setBroadcastDispatcher(dispatcher: ((Context, String, Any) -> Unit)?) {
        RegistrationCoordinator.broadcastDispatcher = dispatcher?.let { d ->
            { ctx, action, binder -> d(ctx, action, binder) }
        }
    }

    fun setForceSupported(force: Boolean) {
        LyriconFactory.isForceSupported = force
    }

    fun resetBootReceiver() {
        CentralBootReceiver.resetForTesting()
    }

    fun addBootListener(listener: () -> Unit): Any {
        val l = object : CentralBootReceiver.BootListener {
            override fun onBootCompleted() = listener()
        }
        CentralBootReceiver.addBootListener(l)
        return l
    }

    fun removeBootListener(token: Any) {
        if (token is CentralBootReceiver.BootListener) {
            CentralBootReceiver.removeBootListener(token)
        }
    }

    fun simulateServiceLost(provider: LyriconProvider) {
        (provider as? LyriconProviderImpl)?.centralConnection?.simulateServiceLost()
    }
}
