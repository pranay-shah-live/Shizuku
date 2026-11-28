package moe.shizuku.manager.stealth

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.UserHandleCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

data class StealthAppProfile(
    val packageName: String,
    val hideDevOptions: Boolean = true,
    val hideUsbDebugging: Boolean = true,
    val hideWifiDebugging: Boolean = true,
    val hideAccessibility: Boolean = true,
    val enabled: Boolean = true
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("packageName", packageName)
        obj.put("hideDevOptions", hideDevOptions)
        obj.put("hideUsbDebugging", hideUsbDebugging)
        obj.put("hideWifiDebugging", hideWifiDebugging)
        obj.put("hideAccessibility", hideAccessibility)
        obj.put("enabled", enabled)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): StealthAppProfile? {
            return try {
                val obj = JSONObject(jsonStr)
                StealthAppProfile(
                    packageName = obj.getString("packageName"),
                    hideDevOptions = obj.optBoolean("hideDevOptions", true),
                    hideUsbDebugging = obj.optBoolean("hideUsbDebugging", true),
                    hideWifiDebugging = obj.optBoolean("hideWifiDebugging", true),
                    hideAccessibility = obj.optBoolean("hideAccessibility", true),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Throwable) {
                null
            }
        }
    }
}

data class SavedSettingsState(
    val devOptions: Int,
    val usbDebugging: Int,
    val wifiDebugging: Int,
    val accessibility: Int
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("devOptions", devOptions)
        obj.put("usbDebugging", usbDebugging)
        obj.put("wifiDebugging", wifiDebugging)
        obj.put("accessibility", accessibility)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SavedSettingsState? {
            return try {
                val obj = JSONObject(jsonStr)
                SavedSettingsState(
                    devOptions = obj.optInt("devOptions", 1),
                    usbDebugging = obj.optInt("usbDebugging", 1),
                    wifiDebugging = obj.optInt("wifiDebugging", 1),
                    accessibility = obj.optInt("accessibility", 1)
                )
            } catch (e: Throwable) {
                null
            }
        }
    }
}

object AppProfileManager {
    private const val TAG = "AppProfileManager"
    private const val PREF_PROFILES_PREFIX = "stealth_profile_"
    private const val PREF_PROFILES_LIST = "stealth_configured_packages"
    private const val PREF_SAVED_STATE = "stealth_saved_settings_state"
    private const val PREF_ACTIVE_PACKAGE = "stealth_active_package"

    private val appContext: Context get() = ShizukuApplication.appContext

    fun getProfile(packageName: String): StealthAppProfile {
        val prefs = ShizukuSettings.getPreferences() ?: return StealthAppProfile(packageName)
        val json = prefs.getString(PREF_PROFILES_PREFIX + packageName, null)
        return if (json != null) {
            StealthAppProfile.fromJson(json) ?: StealthAppProfile(packageName)
        } else {
            StealthAppProfile(packageName)
        }
    }

    fun saveProfile(profile: StealthAppProfile) {
        val prefs = ShizukuSettings.getPreferences() ?: return
        val currentPackages = getSavedPackages().toMutableSet()
        currentPackages.add(profile.packageName)

        prefs.edit()
            .putString(PREF_PROFILES_PREFIX + profile.packageName, profile.toJson())
            .putStringSet(PREF_PROFILES_LIST, currentPackages)
            .apply()
    }

    fun removeProfile(packageName: String) {
        val prefs = ShizukuSettings.getPreferences() ?: return
        val currentPackages = getSavedPackages().toMutableSet()
        currentPackages.remove(packageName)

        prefs.edit()
            .remove(PREF_PROFILES_PREFIX + packageName)
            .putStringSet(PREF_PROFILES_LIST, currentPackages)
            .apply()
    }

    fun getSavedPackages(): Set<String> {
        return ShizukuSettings.getPreferences()
            ?.getStringSet(PREF_PROFILES_LIST, emptySet())
            ?: emptySet()
    }

    fun getActivePackage(): String? {
        return ShizukuSettings.getPreferences()?.getString(PREF_ACTIVE_PACKAGE, null)
    }

    fun isMaskingActive(): Boolean {
        return getActivePackage() != null
    }

    private fun readCurrentSettings(): SavedSettingsState {
        val cr = appContext.contentResolver
        val dev = try { Settings.Global.getInt(cr, "development_settings_enabled", 1) } catch (e: Throwable) { 1 }
        val usb = try { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 1) } catch (e: Throwable) { 1 }
        val wifi = try { Settings.Global.getInt(cr, "adb_wifi_enabled", 1) } catch (e: Throwable) { 1 }
        val acc = try { Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1) } catch (e: Throwable) { 1 }
        return SavedSettingsState(dev, usb, wifi, acc)
    }

    private fun writeGlobalSetting(key: String, value: Int): Boolean {
        return try {
            if (appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(appContext.contentResolver, key, value)
            } else {
                writeViaShell("settings put global $key $value")
            }
        } catch (e: Throwable) {
            writeViaShell("settings put global $key $value")
        }
    }

    private fun writeSecureSetting(key: String, value: Int): Boolean {
        return try {
            if (appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Secure.putInt(appContext.contentResolver, key, value)
            } else {
                writeViaShell("settings put secure $key $value")
            }
        } catch (e: Throwable) {
            writeViaShell("settings put secure $key $value")
        }
    }

    private fun writeViaShell(command: String): Boolean {
        if (!Shizuku.pingBinder()) return false
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            p.waitFor() == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to run shell command: $command", e)
            false
        }
    }

    @Synchronized
    fun applyProfile(profile: StealthAppProfile): Boolean {
        val prefs = ShizukuSettings.getPreferences() ?: return false

        // Save original settings if not already masking
        if (!isMaskingActive()) {
            val original = readCurrentSettings()
            prefs.edit()
                .putString(PREF_SAVED_STATE, original.toJson())
                .putString(PREF_ACTIVE_PACKAGE, profile.packageName)
                .apply()
        }

        var success = true
        if (profile.hideDevOptions) {
            success = writeGlobalSetting("development_settings_enabled", 0) && success
        }
        if (profile.hideUsbDebugging) {
            success = writeGlobalSetting(Settings.Global.ADB_ENABLED, 0) && success
        }
        if (profile.hideWifiDebugging) {
            success = writeGlobalSetting("adb_wifi_enabled", 0) && success
        }
        if (profile.hideAccessibility) {
            success = writeSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, 0) && success
        }

        Log.i(TAG, "Applied stealth profile for ${profile.packageName}, success=$success")
        return success
    }

    @Synchronized
    fun revertSettings(): Boolean {
        val prefs = ShizukuSettings.getPreferences() ?: return false
        val savedJson = prefs.getString(PREF_SAVED_STATE, null)
        val saved = if (savedJson != null) SavedSettingsState.fromJson(savedJson) else null

        val dev = saved?.devOptions ?: 1
        val usb = saved?.usbDebugging ?: 1
        val wifi = saved?.wifiDebugging ?: 1
        val acc = saved?.accessibility ?: 1

        var success = true
        success = writeGlobalSetting("development_settings_enabled", dev) && success
        success = writeGlobalSetting(Settings.Global.ADB_ENABLED, usb) && success
        success = writeGlobalSetting("adb_wifi_enabled", wifi) && success
        success = writeSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, acc) && success

        prefs.edit()
            .remove(PREF_SAVED_STATE)
            .remove(PREF_ACTIVE_PACKAGE)
            .apply()

        Log.i(TAG, "Reverted stealth settings: dev=$dev, usb=$usb, wifi=$wifi, acc=$acc")
        return success
    }

    fun forceStopPackage(packageName: String, userId: Int = UserHandleCompat.myUserId()): Boolean {
        val binder = Shizuku.getBinder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeString(packageName)
            data.writeInt(userId)
            binder.transact(ServerConstants.BINDER_TRANSACTION_forceStopPackage, data, reply, 0)
            reply.readException()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to force stop $packageName", e)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
