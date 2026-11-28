package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku

private val appContext = ShizukuApplication.appContext

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private var state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                set(State.RUNNING)
                onServerStarted()
            }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener { setDead() }
        )
    }

    private fun onServerStarted() {
        try {
            if (appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                // Aggressive stealth: zero out all developer/ADB flags so RASP/UPI detectors see a clean state.
                // Note: AdbStarter/AdbStartWorker write ADB_ENABLED = 1 *before* the binder is received,
                // so by the time OnBinderReceivedListener fires the wireless ADB pairing session is
                // already established. It is safe to zero ADB_ENABLED here — adbd keeps the socket alive
                // independently of the Settings value once the session is running.
                Settings.Global.putInt(appContext.contentResolver, "development_settings_enabled", 0)
                Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
                Settings.Global.putInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0)
                Log.i("ShizukuStateMachine", "Aggressive stealth: zeroed development_settings_enabled, adb_enabled, adb_wifi_enabled")
            }
        } catch (e: Throwable) {
            Log.w("ShizukuStateMachine", "Failed to zero out dev settings", e)
        }
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        val oldState = state.getAndUpdate(transform)
        val newState = transform(oldState)
        if(oldState != newState) {
            listeners.forEach { it(newState) }
            Log.d("ShizukuStateMachine", newState.toString())
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                try {
                    val permissionGranted = appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        Settings.Global.putInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0)
                    }
                } catch (e: Exception) {
                    Log.w("ShizukuStateMachine", "Failed to disable USB debugging", e)
                }
                State.STOPPED
            }
            else -> it
        }
    }

    fun update(): State {
        val state = if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED) 
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}