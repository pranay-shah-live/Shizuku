package moe.shizuku.manager.utils.ApkUtils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.archive.FileInputSource
import com.reandroid.common.Namespace
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.ApkSigner
import java.io.File

private const val TAG = "ApkUtils"

const val ORIGINAL_PACKAGE_NAME = "moe.shizuku.privileged.api"

private val app = ShizukuApplication.application
private val appContext = ShizukuApplication.appContext

val workDir by lazy {
    File(appContext.cacheDir, "patcher").also {
        it.deleteRecursively()
        it.mkdirs()
    }
}

fun File.changePackageName(newPkgName: String, maybeCreateSigningKey: Boolean = false): File {
    Log.i(TAG, "Loading APK")
    val module = ApkModule.loadApkFile(this)
    val manifest = module.androidManifest

    Log.i(TAG, "Changing package name and permissions")
    val oldPkgName = manifest.packageName
    manifest.packageName = newPkgName

    // Rewrite custom permissions and permission-groups
    val permissions = manifest.getElements("permission")
    for (p in permissions) {
        val attr = p.searchAttribute(Namespace.URI_ANDROID, "name")
        val name = attr?.valueAsString ?: continue
        if (name.startsWith(oldPkgName) || name.startsWith("moe.shizuku")) {
            val suffix = name.substringAfterLast('.')
            attr.setValueAsString("$newPkgName.permission.$suffix")
        }
        val groupAttr = p.searchAttribute(Namespace.URI_ANDROID, "permissionGroup")
        val group = groupAttr?.valueAsString
        if (group != null && (group.startsWith(oldPkgName) || group.startsWith("moe.shizuku"))) {
            groupAttr.setValueAsString("$newPkgName.permission-group.API")
        }
    }

    val permGroups = manifest.getElements("permission-group")
    for (pg in permGroups) {
        val attr = pg.searchAttribute(Namespace.URI_ANDROID, "name")
        val name = attr?.valueAsString ?: continue
        if (name.startsWith(oldPkgName) || name.startsWith("moe.shizuku")) {
            attr.setValueAsString("$newPkgName.permission-group.API")
        }
    }

    val usesPermissions = manifest.getElements("uses-permission")
    for (up in usesPermissions) {
        val attr = up.searchAttribute(Namespace.URI_ANDROID, "name")
        val name = attr?.valueAsString ?: continue
        if (name.startsWith(oldPkgName) || name.startsWith("moe.shizuku")) {
            val suffix = name.substringAfterLast('.')
            attr.setValueAsString("$newPkgName.permission.$suffix")
        }
    }

    Log.i(TAG, "Updating provider authorities and intent actions")
    val appElement = manifest.getApplicationElement()
    val providers = appElement.getElements("provider")
    for (provider in providers) {
        val attr = provider.searchAttribute(Namespace.URI_ANDROID, "authorities")
        val auth = attr?.valueAsString ?: continue

        if (auth.startsWith(oldPkgName)) {
            val newAuth = auth.replace(oldPkgName, newPkgName)
            attr.setValueAsString(newAuth)
        }
    }

    // Rewrite intent-filter actions containing old package name
    val activities = appElement.getElements("activity") + appElement.getElements("receiver")
    for (comp in activities) {
        val filters = comp.getElements("intent-filter")
        for (f in filters) {
            val actions = f.getElements("action")
            for (action in actions) {
                val attr = action.searchAttribute(Namespace.URI_ANDROID, "name")
                val actName = attr?.valueAsString ?: continue
                if (actName.startsWith(oldPkgName)) {
                    attr.setValueAsString(actName.replace(oldPkgName, newPkgName))
                }
            }
        }
    }

    Log.i(TAG, "Inserting signing key")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    val keystore = ApkSigner.keystoreFile
    val keyInputSource = FileInputSource(keystore, "assets/${keystore.name}")
    module.add(keyInputSource)

    val outFile = File(workDir, "signed.apk")
    return module.buildAndSign(outFile)
}

fun createStubApk(pkgName: String): File {
    Log.i(TAG, "Initializing APK framework")
    val outFile = File(appContext.filesDir, "stub.apk")

    val tableBlock = TableBlock()
    val manifest = AndroidManifestBlock()
    
    // Minimal valid classes.dex header: 112 bytes
    val validDex = byteArrayOf(
        0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00, // magic "dex\n035\0"
        0x00, 0x00, 0x00, 0x00,                         // checksum
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // signature (20 bytes)
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x70, 0x00, 0x00, 0x00,                         // file_size = 112
        0x70, 0x00, 0x00, 0x00,                         // header_size = 112
        0x78, 0x56, 0x34, 0x12,                         // endian_tag = 0x12345678
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // link_size, link_off
        0x00, 0x00, 0x00, 0x00,                         // map_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // string_ids_size, string_ids_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // type_ids_size, type_ids_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // proto_ids_size, proto_ids_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // field_ids_size, field_ids_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // method_ids_size, method_ids_off
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // class_defs_size, class_defs_off
        0x00, 0x00, 0x00, 0x00, 0x70, 0x00, 0x00, 0x00  // data_size, data_off
    )
    val dummyDex = ByteInputSource(validDex, "classes.dex")

    val module =
        ApkModule().apply {
            setTableBlock(tableBlock)
            setManifest(manifest)
            add(dummyDex)
        }

    Log.i(TAG, "Adding APK resources")
    val packageBlock = tableBlock.newPackage(0x7f, pkgName)
    val appName =
        packageBlock.getOrCreate("", "string", "app_name").apply {
            setValueAsString("${getAppLabel()} Stub")
        }
    val appIcon =
        packageBlock.getOrCreate("", "drawable", "ic_launcher").apply {
            setValueAsReference(R.drawable.ic_launcher)
        }

    Log.i(TAG, "Creating manifest")
    manifest.apply {
        setPackageName(pkgName)
        setVersionCode(1)
        setVersionName("1.0.0")
        setApplicationLabel(appName.getResourceId())
        setIconResourceId(appIcon.getResourceId())
        setTargetSdkVersion(app.applicationInfo.targetSdkVersion)
        setMinSdkVersion(app.applicationInfo.minSdkVersion)
    }

    return module.buildAndSign(outFile, maybeCreateSigningKey = true)
}

private fun ApkModule.buildAndSign(
    outFile: File,
    maybeCreateSigningKey: Boolean = false
): File {
    Log.i(TAG, "Building new APK")
    val unsignedApk = File(workDir, "unsigned.apk")
    writeApk(unsignedApk)

    Log.i(TAG, "Signing APK")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    ApkSigner.sign(unsignedApk, outFile, key)

    return outFile
}

fun getAppLabel(): String =
    app
        .applicationInfo
        .loadLabel(appContext.packageManager)
        .toString()

fun getVersionName(): String =
    appContext
        .packageManager
        .getPackageInfo(app.packageName, 0)
        .versionName
        .orEmpty()

fun buildApkFilename(): String {
    val safeLabel =
        getAppLabel()
            .lowercase()
            .replace("[^a-z0-9._-]".toRegex(), "-")

    return "$safeLabel-${getVersionName()}"
}

fun Context.installPackage(
    apk: File,
    cb: ((Boolean, String?) -> Unit)? = null
) {
    val installer = appContext.packageManager.packageInstaller

    val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = installer.createSession(sessionParams)
    val session = installer.openSession(sessionId)

    apk.inputStream().use { input ->
        session.openWrite("base.apk", 0, apk.length()).use { output ->
            input.copyTo(output)
            session.fsync(output)
        }
    }

    val pendingIntent = createInstallerPendingIntent(sessionId, cb)

    session.commit(pendingIntent.intentSender)
    session.close()
}

fun Context.uninstallPackage(
    pkgName: String,
    cb: ((Boolean, String?) -> Unit)? = null,
) {
    val installer = appContext.packageManager.packageInstaller
    val pendingIntent = createInstallerPendingIntent(0, cb)
    installer.uninstall(pkgName, pendingIntent.intentSender)
}

private var callback: ((Boolean, String?) -> Unit)? = null

val installerReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmationIntent != null) {
                        context.startActivity(confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                else -> {
                    val isSuccess = (status == PackageInstaller.STATUS_SUCCESS)
                    callback?.invoke(isSuccess, msg)
                    context.unregisterReceiver(this)
                }
            }
        }
    }

private fun Context.createInstallerPendingIntent(
    sessionId: Int,
    cb: ((Boolean, String?) -> Unit)? = null
): PendingIntent {
    callback = cb

    val installerAction = "${app.packageName}.INSTALLER_RESULT"
    val filter =
        IntentFilter().apply {
            addAction(installerAction)
        }
    registerReceiver(installerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

    val callbackIntent =
        Intent(installerAction).apply {
            setPackage(app.packageName)
        }

    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    return PendingIntent.getBroadcast(
        this,
        sessionId,
        callbackIntent,
        flags,
    )
}
