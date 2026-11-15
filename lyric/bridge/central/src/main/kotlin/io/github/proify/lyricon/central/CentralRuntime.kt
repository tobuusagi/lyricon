/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central

import io.github.proify.lyricon.central.internal.player.ActivePlayerHub
import io.github.proify.lyricon.central.internal.provider.ProviderDirectory
import io.github.proify.lyricon.central.internal.registration.RegistrationDispatcher
import io.github.proify.lyricon.central.internal.subscriber.SubscriberDirectory

/**
 * 中央运行时：全局单例的注册表与分发器集合。
 */
internal object CentralRuntime {
    var activePlayers = ActivePlayerHub()
    var providers = ProviderDirectory(activePlayers)
    var subscribers = SubscriberDirectory()
    var registration = RegistrationDispatcher(providers, subscribers)

    fun resetForTesting() {
        activePlayers = ActivePlayerHub()
        providers = ProviderDirectory(activePlayers)
        subscribers = SubscriberDirectory()
        registration = RegistrationDispatcher(providers, subscribers)
    }
}
