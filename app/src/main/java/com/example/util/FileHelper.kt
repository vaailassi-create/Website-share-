package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.model.FileCategory
import com.example.model.SharedFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object FileHelper {

    fun getCategoryForMime(mimeType: String, extension: String): FileCategory {
        return when {
            extension == "APK" || mimeType == "application/vnd.android.package-archive" -> FileCategory.APPS
            mimeType.startsWith("image/") || extension in listOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "SVG") -> FileCategory.IMAGE
            mimeType.startsWith("video/") || extension in listOf("MP4", "MKV", "WEBM", "AVI", "MOV") -> FileCategory.VIDEO
            mimeType.startsWith("audio/") || extension in listOf("MP3", "WAV", "OGG", "M4A", "FLAC") -> FileCategory.AUDIO
            mimeType.startsWith("text/") || extension in listOf("PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX", "TXT", "MD", "JSON") -> FileCategory.DOCUMENT
            else -> FileCategory.ALL
        }
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "json" -> "application/json"
            "md", "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    fun createSampleFiles(context: Context): List<SharedFile> {
        val samplesDir = File(context.filesDir, "shared_samples").apply { mkdirs() }
        val sampleList = mutableListOf<SharedFile>()

        // 1. Welcome Guide document
        val guideFile = File(samplesDir, "WebShare_User_Guide.txt")
        if (!guideFile.exists() || guideFile.length() == 0L) {
            guideFile.writeText(
                """
                =====================================================
                WELCOME TO WEB SHARE MY FILE
                =====================================================
                
                Features:
                1. Local High-Speed Web Sharing:
                   - Share files to any PC, Mac, iPhone, or Android device on the same Wi-Fi.
                   - Recipients don't need any app installed; they just open the web browser!
                
                2. Name Change via Web ("Name Change by Web"):
                   - Clients browsing on the web can click 'Rename' on any file to change its name!
                   - The phone's sharing station name can also be updated directly from the web browser.
                
                3. Send to Phone (Upload):
                   - Drop files on the web page to transfer them directly onto your Android device.
                
                4. Live Clipboard & Messaging:
                   - Send notes, links, and messages back and forth seamlessly.
                
                =====================================================
                """.trimIndent()
            )
        }
        sampleList.add(
            SharedFile(
                id = "sample_guide",
                name = guideFile.name,
                size = guideFile.length(),
                mimeType = "text/plain",
                category = FileCategory.DOCUMENT,
                file = guideFile,
                dateModified = guideFile.lastModified(),
                isReceivedFromWeb = false
            )
        )

        // 2. Project Specifications Markdown
        val notesFile = File(samplesDir, "Project_Roadmap.md")
        if (!notesFile.exists() || notesFile.length() == 0L) {
            notesFile.writeText(
                """
                # Wireless File Transfer Specifications
                
                ## Milestones
                - [x] Embedded HTTP server running on Android local Wi-Fi port
                - [x] Interactive web client with category filtering & instant download
                - [x] Bi-directional file transfer (Android -> Web and Web -> Android)
                - [x] Remote file renaming ("Name change by web")
                - [x] Responsive QR code generator for one-tap camera connection
                - [x] Real-time message exchange
                """.trimIndent()
            )
        }
        sampleList.add(
            SharedFile(
                id = "sample_roadmap",
                name = notesFile.name,
                size = notesFile.length(),
                mimeType = "text/markdown",
                category = FileCategory.DOCUMENT,
                file = notesFile,
                dateModified = notesFile.lastModified(),
                isReceivedFromWeb = false
            )
        )

        // 3. Vector Graphic / Art
        val svgFile = File(samplesDir, "Network_Transfer_Diagram.svg")
        if (!svgFile.exists() || svgFile.length() == 0L) {
            svgFile.writeText(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="400" height="200">
                  <rect width="100%" height="100%" fill="#0B132B"/>
                  <circle cx="100" cy="100" r="50" fill="#1C2541" stroke="#00B4D8" stroke-width="4"/>
                  <text x="100" y="105" fill="#FFFFFF" font-family="sans-serif" font-size="14" text-anchor="middle">Phone</text>
                  <circle cx="300" cy="100" r="50" fill="#1C2541" stroke="#10B981" stroke-width="4"/>
                  <text x="300" y="105" fill="#FFFFFF" font-family="sans-serif" font-size="14" text-anchor="middle">Web Browser</text>
                  <path d="M 160 90 L 240 90" stroke="#00B4D8" stroke-width="3" stroke-dasharray="6"/>
                  <path d="M 240 110 L 160 110" stroke="#10B981" stroke-width="3" stroke-dasharray="6"/>
                  <text x="200" y="80" fill="#90E0EF" font-family="sans-serif" font-size="12" text-anchor="middle">Share & Rename</text>
                  <text x="200" y="130" fill="#A7F3D0" font-family="sans-serif" font-size="12" text-anchor="middle">Upload to Phone</text>
                </svg>
                """.trimIndent()
            )
        }
        sampleList.add(
            SharedFile(
                id = "sample_svg",
                name = svgFile.name,
                size = svgFile.length(),
                mimeType = "image/svg+xml",
                category = FileCategory.IMAGE,
                file = svgFile,
                dateModified = svgFile.lastModified(),
                isReceivedFromWeb = false
            )
        )

        // 4. Sample JSON Data
        val jsonFile = File(samplesDir, "Network_Config.json")
        if (!jsonFile.exists() || jsonFile.length() == 0L) {
            jsonFile.writeText(
                """
                {
                  "appName": "Web Share My File",
                  "version": "1.0",
                  "protocol": "HTTP/1.1",
                  "defaultPort": 8080,
                  "supportedOperations": [
                    "download",
                    "upload",
                    "rename_by_web",
                    "realtime_chat"
                  ]
                }
                """.trimIndent()
            )
        }
        sampleList.add(
            SharedFile(
                id = "sample_json",
                name = jsonFile.name,
                size = jsonFile.length(),
                mimeType = "application/json",
                category = FileCategory.DOCUMENT,
                file = jsonFile,
                dateModified = jsonFile.lastModified(),
                isReceivedFromWeb = false
            )
        )

        // 5. Sample APK Package File
        val apkFile = File(samplesDir, "WebShare_Utility_v1.0.apk")
        if (!apkFile.exists() || apkFile.length() == 0L) {
            // Write standard zip/apk archive header
            apkFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        }
        sampleList.add(
            SharedFile(
                id = "sample_apk",
                name = apkFile.name,
                size = if (apkFile.length() > 0) apkFile.length() else 1024L * 1024L * 4L,
                mimeType = "application/vnd.android.package-archive",
                category = FileCategory.APPS,
                file = apkFile,
                dateModified = apkFile.lastModified(),
                isReceivedFromWeb = false,
                packageName = "com.example.webshare.utility",
                appVersion = "1.0"
            )
        )

        return sampleList
    }

    fun copyUriToAppStorage(context: Context, uri: Uri): SharedFile? {
        return try {
            val contentResolver = context.contentResolver
            var fileName = "file_${System.currentTimeMillis()}"
            var fileSize = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex) ?: fileName
                    }
                    if (sizeIndex >= 0) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            val mimeType = contentResolver.getType(uri) ?: getMimeType(fileName)
            val extension = fileName.substringAfterLast('.', "")
            val sharedDir = File(context.filesDir, "shared_picked").apply { mkdirs() }
            val targetFile = File(sharedDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (fileSize <= 0) {
                fileSize = targetFile.length()
            }

            SharedFile(
                id = UUID.randomUUID().toString(),
                name = targetFile.name,
                size = fileSize,
                mimeType = mimeType,
                category = getCategoryForMime(mimeType, extension.uppercase()),
                file = targetFile,
                contentUri = uri.toString(),
                dateModified = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }
}
