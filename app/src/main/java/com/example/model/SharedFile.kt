package com.example.model

import java.io.File

enum class FileCategory(val label: String) {
    ALL("All Files"),
    APPS("Apps (APKs)"),
    DOCUMENT("Documents"),
    IMAGE("Photos"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    RECEIVED("Received from Web")
}

data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val category: FileCategory,
    val file: File? = null,
    val contentUri: String? = null,
    val dateModified: Long = System.currentTimeMillis(),
    val isReceivedFromWeb: Boolean = false,
    val packageName: String? = null,
    val appVersion: String? = null
) {
    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            val formatted = String.format("%.1f", size / Math.pow(1024.0, digitGroups.toDouble()))
            return "$formatted ${units.getOrElse(digitGroups) { "B" }}"
        }

    val extension: String
        get() = name.substringAfterLast('.', "").uppercase()

    val isApk: Boolean
        get() = extension == "APK" || category == FileCategory.APPS || mimeType == "application/vnd.android.package-archive"
}

data class WebMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromPhone: Boolean = true
)

data class ServerStatus(
    val isRunning: Boolean = false,
    val ipAddress: String = "",
    val port: Int = 8080,
    val deviceName: String = "Android Device",
    val connectedClients: Int = 0,
    val totalDownloads: Int = 0,
    val totalUploads: Int = 0,
    val activeWifiSsid: String = ""
) {
    val serverUrl: String
        get() = if (ipAddress.isNotEmpty()) "http://$ipAddress:$port" else "http://localhost:$port"
}

data class ActivityLog(
    val id: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true
)
