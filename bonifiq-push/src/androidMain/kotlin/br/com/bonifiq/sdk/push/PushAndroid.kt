package br.com.bonifiq.sdk.push

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.startup.Initializer
import java.lang.ref.WeakReference

internal actual fun pushLog(message: String) {
    Log.w(PushAndroid.TAG, message)
}

/**
 * Contexto do app e a tela em primeiro plano, guardados na inicialização (androidx.startup), como no módulo Fitness.
 * Assim o `BonifiQPush(bonifiq)` não pede `Context`, e o serviço do FCM funciona antes de o app criar a instância.
 */
internal object PushAndroid {
    const val TAG = "BonifiQPush"

    @Volatile
    private var application: Application? = null

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    val contextOrNull: Context? get() = application

    val context: Context
        get() = application ?: error(
            "BonifiQ Push não foi inicializado: o app removeu o androidx.startup.InitializationProvider do manifest.",
        )

    fun init(app: Application) {
        if (application != null) return
        application = app
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumed?.get() === activity) resumed = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Abre a partir da tela atual (mesma pilha do app); sem tela visível, numa tarefa nova. */
    fun startActivity(intent: Intent) {
        val activity = resumed?.get()
        if (activity != null && !activity.isFinishing) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Registrado no manifest do módulo (InitializationProvider do androidx.startup). */
internal class PushInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        PushAndroid.init(context.applicationContext as Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/** Preferências locais do módulo: o canal da loja (para mostrar notificações sem a config) e se já pedimos permissão. */
internal object PushPrefs {
    private const val CHANNEL_ID = "channel_id"
    private const val CHANNEL_NAME = "channel_name"
    private const val PERMISSION_ASKED = "permission_asked"

    private fun prefs(context: Context) = context.getSharedPreferences("br.com.bonifiq.sdk.push", Context.MODE_PRIVATE)

    fun channelId(context: Context): String? = prefs(context).getString(CHANNEL_ID, null)

    fun channelName(context: Context): String? = prefs(context).getString(CHANNEL_NAME, null)

    fun saveChannel(context: Context, id: String, name: String) {
        prefs(context).edit().putString(CHANNEL_ID, id).putString(CHANNEL_NAME, name).apply()
    }

    fun permissionAsked(context: Context): Boolean = prefs(context).getBoolean(PERMISSION_ASKED, false)

    fun setPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(PERMISSION_ASKED, true).apply()
    }
}
