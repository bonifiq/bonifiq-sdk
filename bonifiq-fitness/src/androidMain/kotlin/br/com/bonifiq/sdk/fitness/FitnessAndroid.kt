package br.com.bonifiq.sdk.fitness

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.startup.Initializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.lang.ref.WeakReference

internal actual fun platformHealthSource(): HealthSource = HealthConnectSource { FitnessAndroid.context }

internal actual fun appResumedEvents(): Flow<Unit> = FitnessAndroid.resumes

internal actual fun platformFitnessStore(): FitnessStore = object : FitnessStore {
    // Sem a inicialização do módulo (InitializationProvider removido), não guarda nada.
    private val prefs by lazy {
        runCatching { FitnessAndroid.context.getSharedPreferences("br.com.bonifiq.sdk.fitness", Context.MODE_PRIVATE) }
            .getOrNull()
    }

    override fun getString(key: String): String? = prefs?.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }
}

/**
 * Contexto do app e a tela em primeiro plano, guardados na inicialização (androidx.startup). Assim o
 * `BonifiQFitness()` não pede `Context` e funciona igual em código comum (KMP).
 */
internal object FitnessAndroid {
    private var application: Application? = null
    private var resumed: WeakReference<Activity>? = null
    private var startedActivities = 0

    /** Volta do app ao primeiro plano (não conta a troca entre telas do próprio app). */
    val resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val context: Context
        get() = application ?: error(
            "BonifiQ Fitness não foi inicializado: o app removeu o androidx.startup.InitializationProvider do manifest.",
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

                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities++ == 0) resumes.tryEmit(Unit)
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
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
internal class FitnessInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        FitnessAndroid.init(context.applicationContext as Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
