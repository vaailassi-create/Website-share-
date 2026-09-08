package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.model.FileCategory
import com.example.model.SharedFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class InstalledApp(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val apkSize: Long,
    val sourceDir: String,
    val isSystemApp: Boolean,
    val isCurrentApp: Boolean = false
) {
    val formattedSize: String
        get() {
            if (apkSize <= 0) return "0 B"
            val kb = apkSize / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$apkSize B"
            }
        }
}

object AppInstallHelper {

    /**
     * Launch the Android Package Installer on mobile to install the specified APK file.
     */
    fun installApk(context: Context, apkFile: File): Result<Boolean> {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                return Result.failure(Exception("APK file does not exist or is empty"))
            }

            // Ensure the APK is located in an internal cache or files directory readable by FileProvider
            val targetDir = File(context.filesDir, "install_apks").apply { mkdirs() }
            val installableFile = if (apkFile.parentFile == targetDir) {
                apkFile
            } else {
                val dest = File(targetDir, apkFile.name)
                if (!dest.exists() || dest.length() != apkFile.length()) {
                    FileInputStream(apkFile).use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                dest
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, installableFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if Android 8.0+ Unknown App Sources permission is granted
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Open Unknown Apps settings for this application
     */
    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general settings
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Scan and query installed applications from mobile device.
     */
    fun getInstalledMobileApps(context: Context): List<SharedFile> {
        val pm = context.packageManager
        val appList = mutableListOf<SharedFile>()

        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val hasLaunchIntent = pm.getLaunchIntentForPackage(pkg.packageName) != null

                // Include user installed apps or launchable apps
                if (!isSystemApp || isUpdatedSystem || hasLaunchIntent) {
                    val appName = appInfo.loadLabel(pm).toString()
                    val sourceDir = appInfo.sourceDir
                    if (sourceDir != null) {
                        val apkFile = File(sourceDir)
                        if (apkFile.exists() && apkFile.length() > 0) {
                            val cleanName = appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            appList.add(
                                SharedFile(
                                    id = "app_${pkg.packageName}",
                                    name = "$cleanName.apk",
                                    size = apkFile.length(),
                                    mimeType = "application/vnd.android.package-archive",
                                    category = FileCategory.APPS,
                                    file = apkFile,
                                    dateModified = apkFile.lastModified(),
                                    packageName = pkg.packageName,
                                    appVersion = pkg.versionName ?: "1.0"
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Permission or package manager query limitation
        }

        return appList
    }

    /**
     * Get a comprehensive list of installed apps on the Android device for the APK Maker.
     */
    fun getInstalledAppsList(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val list = mutableListOf<InstalledApp>()
        val currentPackage = context.packageName

        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdated = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isCurrent = pkg.packageName == currentPackage
                val hasLaunchIntent = pm.getLaunchIntentForPackage(pkg.packageName) != null

                // Include launchable apps, user apps, current app, or updated system apps
                if (isCurrent || !isSystem || isUpdated || hasLaunchIntent) {
                    val label = appInfo.loadLabel(pm).toString()
                    val src = appInfo.sourceDir ?: ""
                    val size = if (src.isNotEmpty()) File(src).length() else 0L

                    list.add(
                        InstalledApp(
                            appName = if (isCurrent) "$label (This App)" else label,
                            packageName = pkg.packageName,
                            versionName = pkg.versionName ?: "1.0",
                            apkSize = size,
                            sourceDir = src,
                            isSystemApp = isSystem && !isUpdated,
                            isCurrentApp = isCurrent
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Package manager query limit handling
        }

        return list.sortedWith(
            compareByDescending<InstalledApp> { it.isCurrentApp }
                .thenBy { it.appName.lowercase() }
        )
    }

    /**
     * Extracts and makes a standalone APK file from an installed Android application.
     * Saves the resulting APK into the internal files/made_apks directory so it can be downloaded
     * or shared directly by Web Share.
     */
    fun makeApkFromApp(context: Context, packageName: String, customFileName: String? = null): Result<SharedFile> {
        return try {
            val pm = context.packageManager
            val pkgInfo = pm.getPackageInfo(packageName, 0)
            val appInfo = pkgInfo.applicationInfo ?: return Result.failure(Exception("App info not found"))
            val sourceDir = appInfo.sourceDir ?: return Result.failure(Exception("APK source directory not found"))
            val sourceFile = File(sourceDir)

            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return Result.failure(Exception("Source APK file not found or empty"))
            }

            val appLabel = appInfo.loadLabel(pm).toString()
            val safeLabel = appLabel.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
            val version = pkgInfo.versionName ?: "1.0"
            val defaultName = "${safeLabel}_v${version}.apk"

            val finalName = if (!customFileName.isNullOrBlank()) {
                if (customFileName.lowercase().endsWith(".apk")) customFileName else "$customFileName.apk"
            } else {
                defaultName
            }

            val outputDir = File(context.filesDir, "made_apks").apply { mkdirs() }
            val outputFile = File(outputDir, finalName)

            // Copy the APK cleanly into the app-managed directory
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sharedFile = SharedFile(
                id = "made_${UUID.randomUUID()}",
                name = outputFile.name,
                size = outputFile.length(),
                mimeType = "application/vnd.android.package-archive",
                category = FileCategory.APPS,
                file = outputFile,
                dateModified = outputFile.lastModified(),
                isReceivedFromWeb = false,
                packageName = packageName,
                appVersion = version
            )

            Result.success(sharedFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a new custom APK file archive with Android Manifest and structure.
     */
    fun createCustomApkPackage(
        context: Context,
        appName: String,
        packageName: String,
        versionName: String = "1.0"
    ): Result<SharedFile> {
        return try {
            val outputDir = File(context.filesDir, "made_apks").apply { mkdirs() }
            val cleanName = appName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
            val fileName = "${cleanName}_v${versionName}.apk"
            val apkFile = File(outputDir, fileName)

            ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
                // Add AndroidManifest.xml entry
                val manifestEntry = ZipEntry("AndroidManifest.xml")
                zos.putNextEntry(manifestEntry)
                val manifestXml = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$packageName"
    android:versionCode="1"
    android:versionName="$versionName">
    <application
        android:label="$appName"
        android:allowBackup="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>""".trimIndent()
                zos.write(manifestXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Add classes.dex entry
                val dexEntry = ZipEntry("classes.dex")
                zos.putNextEntry(dexEntry)
                val dexHeader = byteArrayOf(
                    0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x70, 0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78
                )
                zos.write(dexHeader)
                zos.closeEntry()

                // Add META-INF/MANIFEST.MF
                val metaEntry = ZipEntry("META-INF/MANIFEST.MF")
                zos.putNextEntry(metaEntry)
                val metaContent = "Manifest-Version: 1.0\nCreated-By: Web Share APK Maker\nBuilt-By: Android\n"
                zos.write(metaContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val sharedFile = SharedFile(
                id = "made_${UUID.randomUUID()}",
                name = apkFile.name,
                size = apkFile.length(),
                mimeType = "application/vnd.android.package-archive",
                category = FileCategory.APPS,
                file = apkFile,
                dateModified = apkFile.lastModified(),
                isReceivedFromWeb = false,
                packageName = packageName,
                appVersion = versionName
            )

            Result.success(sharedFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
