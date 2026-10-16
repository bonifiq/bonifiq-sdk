package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.model.AppConfig

internal actual fun platformPush(): PushPlatform = UnsupportedPushPlatform

internal actual fun pushLog(message: String) {
    println("BonifiQPush: $message")
}

/** Desktop: sem push. O SDK não registra o aparelho e a permissão responde `Denied`. */
internal object UnsupportedPushPlatform : PushPlatform {
    override val name: String? = null
    override val appId: String? = null
    override val appVersion: String? = null
    override val locale: String? = null
    override fun start() = Unit
    override fun onPushConfig(config: AppConfig) = Unit
    override suspend fun permissionStatus(): PushPermissionStatus = PushPermissionStatus.Denied
    override suspend fun requestPermission(): PushPermissionStatus = PushPermissionStatus.Denied
    override fun openNotificationSettings(): Boolean = false
}
