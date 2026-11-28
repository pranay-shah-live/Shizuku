package moe.shizuku.manager.stealth

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityStealthProfilesBinding
import moe.shizuku.manager.databinding.DialogStealthProfileConfigBinding
import moe.shizuku.manager.databinding.ItemStealthProfileAppBinding
import moe.shizuku.manager.utils.AppIconCache

class StealthProfilesActivity : AppBarActivity() {

    data class AppEntry(
        val appInfo: ApplicationInfo,
        val label: String,
        val packageName: String,
        var profile: StealthAppProfile
    )

    private lateinit var binding: ActivityStealthProfilesBinding
    private val appEntries = ArrayList<AppEntry>()
    private val displayedEntries = ArrayList<AppEntry>()
    private lateinit var adapter: ProfilesAdapter
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStealthProfilesBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.stealth_profiles_title)

        adapter = ProfilesAdapter()
        binding.recyclerView.adapter = adapter

        binding.buttonRevertBanner.setOnClickListener {
            AppProfileManager.revertSettings()
            StealthNotificationReceiver.cancelNotification(this)
            updateActiveBanner()
            Toast.makeText(this, R.string.stealth_settings_reverted, Toast.LENGTH_SHORT).show()
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        updateActiveBanner()
    }

    private fun updateActiveBanner() {
        val activePkg = AppProfileManager.getActivePackage()
        if (activePkg != null) {
            val label = try {
                val ai = packageManager.getApplicationInfo(activePkg, 0)
                ai.loadLabel(packageManager).toString()
            } catch (e: Throwable) {
                activePkg
            }
            binding.activeBanner.isVisible = true
            binding.activeTitle.text = getString(R.string.stealth_banner_title, label)
        } else {
            binding.activeBanner.isVisible = false
        }
    }

    private fun loadApps() {
        binding.progressBar.isVisible = true
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
                .map { ai ->
                    val label = ai.loadLabel(pm).toString()
                    val profile = AppProfileManager.getProfile(ai.packageName)
                    AppEntry(ai, label, ai.packageName, profile)
                }
                .sortedWith(compareByDescending<AppEntry> {
                    AppProfileManager.getSavedPackages().contains(it.packageName)
                }.thenBy { it.label.lowercase() })

            withContext(Dispatchers.Main) {
                appEntries.clear()
                appEntries.addAll(installed)
                filterApps(searchQuery)
                binding.progressBar.isVisible = false
                updateActiveBanner()
            }
        }
    }

    private fun filterApps(query: String) {
        searchQuery = query
        displayedEntries.clear()
        if (query.isBlank()) {
            displayedEntries.addAll(appEntries)
        } else {
            val lower = query.lowercase()
            displayedEntries.addAll(
                appEntries.filter {
                    it.label.lowercase().contains(lower) || it.packageName.lowercase().contains(lower)
                }
            )
        }
        binding.emptyView.isVisible = displayedEntries.isEmpty()
        adapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.stealth_profiles_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText.orEmpty())
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showProfileConfigDialog(entry: AppEntry) {
        val dialogBinding = DialogStealthProfileConfigBinding.inflate(layoutInflater)
        val currentProfile = entry.profile

        dialogBinding.switchHideDevOptions.isChecked = currentProfile.hideDevOptions
        dialogBinding.switchHideUsbDebugging.isChecked = currentProfile.hideUsbDebugging
        dialogBinding.switchHideWifiDebugging.isChecked = currentProfile.hideWifiDebugging
        dialogBinding.switchHideAccessibility.isChecked = currentProfile.hideAccessibility

        MaterialAlertDialogBuilder(this)
            .setTitle(entry.label)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updatedProfile = currentProfile.copy(
                    hideDevOptions = dialogBinding.switchHideDevOptions.isChecked,
                    hideUsbDebugging = dialogBinding.switchHideUsbDebugging.isChecked,
                    hideWifiDebugging = dialogBinding.switchHideWifiDebugging.isChecked,
                    hideAccessibility = dialogBinding.switchHideAccessibility.isChecked
                )
                entry.profile = updatedProfile
                AppProfileManager.saveProfile(updatedProfile)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.stealth_action_reset_default) { _, _ ->
                val defaultProfile = StealthAppProfile(entry.packageName)
                entry.profile = defaultProfile
                AppProfileManager.saveProfile(defaultProfile)
                adapter.notifyDataSetChanged()
            }
            .show()
    }

    private fun launchWithStealth(entry: AppEntry) {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(entry.packageName)
        if (launchIntent == null) {
            Toast.makeText(this, R.string.stealth_cannot_launch_app, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            AppProfileManager.applyProfile(entry.profile)
            withContext(Dispatchers.Main) {
                StealthNotificationReceiver.showMaskingNotification(
                    this@StealthProfilesActivity,
                    entry.packageName,
                    entry.label
                )
                updateActiveBanner()
                try {
                    startActivity(launchIntent)
                } catch (e: Throwable) {
                    Toast.makeText(this@StealthProfilesActivity, e.localizedMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private inner class ProfilesAdapter : RecyclerView.Adapter<ProfileViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val itemBinding = ItemStealthProfileAppBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ProfileViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            holder.bind(displayedEntries[position])
        }

        override fun getItemCount(): Int = displayedEntries.size
    }

    private inner class ProfileViewHolder(
        private val itemBinding: ItemStealthProfileAppBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(entry: AppEntry) {
            itemBinding.appTitle.text = entry.label
            itemBinding.appPackage.text = entry.packageName

            itemBinding.appIcon.setImageDrawable(entry.appInfo.loadIcon(packageManager))
            AppIconCache.loadIconBitmapAsync(
                itemView.context,
                entry.appInfo,
                entry.appInfo.uid / 100000,
                itemBinding.appIcon
            )

            var maskedCount = 0
            if (entry.profile.hideDevOptions) maskedCount++
            if (entry.profile.hideUsbDebugging) maskedCount++
            if (entry.profile.hideWifiDebugging) maskedCount++
            if (entry.profile.hideAccessibility) maskedCount++

            itemBinding.appStatus.text = itemView.context.resources.getQuantityString(
                R.plurals.stealth_masked_settings_count,
                maskedCount,
                maskedCount
            )

            itemView.setOnClickListener {
                showProfileConfigDialog(entry)
            }

            itemBinding.buttonLaunch.setOnClickListener {
                launchWithStealth(entry)
            }
        }
    }
}
