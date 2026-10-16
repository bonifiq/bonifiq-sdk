@file:OptIn(ExperimentalForeignApi::class)

package br.com.bonifiq.sdk.push

import br.com.bonifiq.sdk.model.AppConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.registerForRemoteNotifications
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

internal actual fun platformPush(): PushPlatform = IosPushPlatform

internal actual fun pushLog(message: String) {
    println("BonifiQPush: $message")
}

/**
 * iOS: sem Firebase dentro do framework. O app usa o Firebase iOS SDK, que troca o token do APNs pelo token FCM, e
 * repassa o token ([BonifiQPush.setToken]) e os toques ([BonifiQPush.handleNotification]).
 */
internal object IosPushPlatform : PushPlatform {
    override val name: String = "ios"

    override val appId: String? get() = NSBundle.mainBundle.bundleIdentifier

    override val appVersion: String?
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String

    // localeIdentifier vem como "pt_BR" (às vezes com "@calendar=..."); a BonifiQ espera "pt-BR".
    override val locale: String
        get() = NSLocale.currentLocale.localeIdentifier.substringBefore('@').replace('_', '-')

    override fun start() = Unit

    override fun onPushConfig(config: AppConfig) = Unit

    override suspend fun permissionStatus(): PushPermissionStatus = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) continuation.resume(settings?.authorizationStatus.toPermissionStatus())
        }
    }

    override suspend fun requestPermission(): PushPermissionStatus {
        val granted = suspendCancellableCoroutine { continuation ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound,
            ) { granted, error ->
                if (error != null) pushLog("Pedido de notificação falhou: ${error.localizedDescription}")
                if (continuation.isActive) continuation.resume(granted)
            }
        }
        // O APNs entrega o token ao app, e o Firebase iOS SDK o troca pelo token FCM (que o app repassa ao SDK).
        if (granted) dispatch_async(dispatch_get_main_queue()) { UIApplication.sharedApplication.registerForRemoteNotifications() }
        return permissionStatus()
    }

    override fun openNotificationSettings(): Boolean {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
        return true
    }

    private fun UNAuthorizationStatus?.toPermissionStatus(): PushPermissionStatus = when (this) {
        UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral ->
            PushPermissionStatus.Granted
        UNAuthorizationStatusDenied -> PushPermissionStatus.Denied
        else -> PushPermissionStatus.NotDetermined
    }
}
