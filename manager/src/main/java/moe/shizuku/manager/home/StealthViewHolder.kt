package moe.shizuku.manager.home

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcel
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeStealthBinding
import moe.shizuku.manager.stealth.AppProfileManager
import moe.shizuku.manager.stealth.StealthProfilesActivity
import moe.shizuku.manager.stealth.StealthTutorialActivity
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

class StealthViewHolder(
    private val binding: HomeStealthBinding,
    root: View,
    private val scope: CoroutineScope
) : BaseViewHolder<Any?>(root) {

    companion object {
        private const val PREF_TARGET_PACKAGES = "stealth_target_packages"

        fun creator(scope: CoroutineScope) = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStealthBinding.inflate(inflater, outer.root, true)
            StealthViewHolder(inner, outer.root, scope)
        }

        fun getSelectedPackages(): Set<String> {
            return ShizukuSettings.getPreferences()
                ?.getStringSet(PREF_TARGET_PACKAGES, emptySet())
                ?: emptySet()
        }

        fun saveSelectedPackages(packages: Set<String>) {
            ShizukuSettings.getPreferences()
                ?.edit()
                ?.putStringSet(PREF_TARGET_PACKAGES, packages)
                ?.apply()
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
                false
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    init {
        root.setOnClickListener { v: View ->
            v.context.startActivity(Intent(v.context, StealthTutorialActivity::class.java))
        }

        binding.buttonProfiles.setOnClickListener { v ->
            v.context.startActivity(Intent(v.context, StealthProfilesActivity::class.java))
        }

        binding.buttonForceStop.setOnClickListener { v ->
            performForceStop(v.context)
        }
    }

    private data class AppItem(
        val packageName: String,
        val label: String
    )

    private fun showTargetAppSelector(context: Context) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { AppItem(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }

        if (installedApps.isEmpty()) {
            Toast.makeText(context, R.string.home_app_management_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val currentSelected = getSelectedPackages().toMutableSet()
        val appLabels = installedApps.map { "${it.label} (${it.packageName})" }.toTypedArray()
        val checkedItems = installedApps.map { currentSelected.contains(it.packageName) }.toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.stealth_force_stop_dialog_title)
            .setMultiChoiceItems(appLabels, checkedItems) { _, which, isChecked ->
                val pkg = installedApps[which].packageName
                if (isChecked) {
                    currentSelected.add(pkg)
                } else {
                    currentSelected.remove(pkg)
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveSelectedPackages(currentSelected)
                Toast.makeText(context, context.getString(R.string.success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performForceStop(context: Context) {
        val configured = AppProfileManager.getSavedPackages()
        val targets = if (configured.isNotEmpty()) configured else getSelectedPackages()
        if (targets.isEmpty()) {
            Toast.makeText(context, R.string.stealth_force_stop_none_selected, Toast.LENGTH_LONG).show()
            showTargetAppSelector(context)
            return
        }

        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, R.string.home_status_service_not_running, Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            var stoppedCount = 0
            val userId = UserHandleCompat.myUserId()
            for (pkg in targets) {
                if (forceStopPackage(pkg, userId)) {
                    stoppedCount++
                }
            }

            withContext(Dispatchers.Main) {
                val message = context.getString(R.string.stealth_force_stop_success, stoppedCount)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
