package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.example.model.FileCategory
import com.example.model.SharedFile
import com.example.model.WebMessage
import com.example.util.InstalledApp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets

class WebShareServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onFileReceived: (File) -> Unit,
    private val onFileRenamed: (String, String) -> Boolean,
    private val onMessageReceived: (WebMessage) -> Unit,
    private val onDeviceNameChanged: (String) -> Unit,
    private val getSharedFiles: () -> List<SharedFile>,
    private val getMessages: () -> List<WebMessage>,
    private val getDeviceName: () -> String,
    private val onActivityLogged: (String, Boolean) -> Unit,
    private val onInstallRequested: ((String) -> Unit)? = null,
    private val getInstalledApps: (() -> List<InstalledApp>)? = null,
    private val onMakeApkRequested: ((String) -> SharedFile?)? = null,
    private val onCreateCustomApkRequested: ((String, String, String) -> SharedFile?)? = null
) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var clientCount = 0
    private var downloadCount = 0
    private var uploadCount = 0

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket

            serverJob = coroutineScope.launch {
                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        launch {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            }
            onActivityLogged("Web Share Server started on port $port", true)
            true
        } catch (e: Exception) {
            onActivityLogged("Failed to start server on port $port: ${e.message}", false)
            false
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
            serverSocket = null
            onActivityLogged("Web Share Server stopped", true)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return Formatter.formatIpAddress(ipInt)
            }
        } catch (e: Exception) {
            // fallback
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        clientCount++
        try {
            val rawInput = client.getInputStream()
            val out = BufferedOutputStream(client.getOutputStream())
            val reader = BufferedReader(InputStreamReader(rawInput, StandardCharsets.UTF_8))

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val fullPath = parts[1]
            val uri = URI(fullPath)
            val path = uri.path
            val query = uri.query

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            var contentType = ""
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val k = line!!.substring(0, colonIdx).trim().lowercase()
                    val v = line!!.substring(colonIdx + 1).trim()
                    headers[k] = v
                    if (k == "content-length") {
                        contentLength = v.toIntOrNull() ?: 0
                    } else if (k == "content-type") {
                        contentType = v
                    }
                }
            }

            when {
                // Main Web Application Page
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = buildHtmlPage(getDeviceName())
                    sendResponse(out, 200, "OK", "text/html; charset=UTF-8", html.toByteArray(StandardCharsets.UTF_8))
                }

                // API: List files
                method == "GET" && path == "/api/files" -> {
                    val files = getSharedFiles()
                    val jsonArray = JSONArray()
                    files.forEach { file ->
                        val obj = JSONObject().apply {
                            put("id", file.id)
                            put("name", file.name)
                            put("size", file.size)
                            put("formattedSize", file.formattedSize)
                            put("mimeType", file.mimeType)
                            put("category", file.category.name)
                            put("extension", file.extension)
                            put("dateModified", file.dateModified)
                            put("isReceived", file.isReceivedFromWeb)
                            put("isApk", file.isApk)
                            put("packageName", file.packageName ?: "")
                            put("appVersion", file.appVersion ?: "")
                        }
                        jsonArray.put(obj)
                    }
                    sendResponse(out, 200, "OK", "application/json; charset=UTF-8", jsonArray.toString().toByteArray(StandardCharsets.UTF_8))
                }

                // API: Server status
                method == "GET" && path == "/api/status" -> {
                    val status = JSONObject().apply {
                        put("running", true)
                        put("deviceName", getDeviceName())
                        put("fileCount", getSharedFiles().size)
                        put("downloads", downloadCount)
                        put("uploads", uploadCount)
                        put("ip", getLocalIpAddress())
                        put("port", port)
                    }
                    sendResponse(out, 200, "OK", "application/json; charset=UTF-8", status.toString().toByteArray(StandardCharsets.UTF_8))
                }

                // API: Messages
                method == "GET" && path == "/api/messages" -> {
                    val msgs = getMessages()
                    val jsonArray = JSONArray()
                    msgs.forEach { msg ->
                        val obj = JSONObject().apply {
                            put("id", msg.id)
                            put("sender", msg.sender)
                            put("text", msg.text)
                            put("timestamp", msg.timestamp)
                            put("isFromPhone", msg.isFromPhone)
                        }
                        jsonArray.put(obj)
                    }
                    sendResponse(out, 200, "OK", "application/json; charset=UTF-8", jsonArray.toString().toByteArray(StandardCharsets.UTF_8))
                }

                // API: Send message from Web
                method == "POST" && path == "/api/message" -> {
                    val body = readBody(reader, contentLength)
                    val text = try {
                        val json = JSONObject(body)
                        json.optString("text", "")
                    } catch (e: Exception) {
                        body
                    }
                    if (text.isNotBlank()) {
                        val msg = WebMessage(
                            id = System.currentTimeMillis().toString(),
                            sender = "Web Browser",
                            text = text.trim(),
                            timestamp = System.currentTimeMillis(),
                            isFromPhone = false
                        )
                        onMessageReceived(msg)
                        onActivityLogged("New message received from web: $text", true)
                        sendJsonResponse(out, JSONObject().put("success", true))
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Empty text"), 400)
                    }
                }

                // API: Rename File by Web (core user feature request!)
                method == "POST" && path == "/api/rename" -> {
                    val body = readBody(reader, contentLength)
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val queryParams = parseQueryParams(query)
                    val fileId = json.optString("id", queryParams["id"] ?: "")
                    val newName = json.optString("newName", queryParams["newName"] ?: "").trim()

                    if (fileId.isNotBlank() && newName.isNotBlank()) {
                        val success = onFileRenamed(fileId, newName)
                        if (success) {
                            onActivityLogged("File renamed via web to: $newName", true)
                            sendJsonResponse(out, JSONObject().put("success", true).put("newName", newName))
                        } else {
                            sendJsonResponse(out, JSONObject().put("success", false).put("error", "Failed to rename file"), 500)
                        }
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Missing id or newName"), 400)
                    }
                }

                // API: Change Station / Device Name by Web
                method == "POST" && path == "/api/device-name" -> {
                    val body = readBody(reader, contentLength)
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val newDeviceName = json.optString("name", "").trim()
                    if (newDeviceName.isNotBlank()) {
                        onDeviceNameChanged(newDeviceName)
                        onActivityLogged("Station name changed via web to: $newDeviceName", true)
                        sendJsonResponse(out, JSONObject().put("success", true).put("deviceName", newDeviceName))
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false), 400)
                    }
                }

                // API: Request install of APK app on mobile device
                method == "POST" && path == "/api/install-app" -> {
                    val body = readBody(reader, contentLength)
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val queryParams = parseQueryParams(query)
                    val fileId = json.optString("fileId", queryParams["fileId"] ?: "")
                    if (fileId.isNotBlank()) {
                        val file = getSharedFiles().find { it.id == fileId }
                        val fileName = file?.name ?: "Application"
                        onInstallRequested?.invoke(fileId)
                        onActivityLogged("Install requested for '$fileName' on mobile", true)
                        sendJsonResponse(
                            out,
                            JSONObject()
                                .put("success", true)
                                .put("message", "App install prompt sent to mobile device for $fileName")
                        )
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Missing fileId"), 400)
                    }
                }

                // API: Get installed apps on Android device to make APK
                method == "GET" && path == "/api/installed-apps" -> {
                    val apps = getInstalledApps?.invoke() ?: emptyList()
                    val jsonArray = JSONArray()
                    for (app in apps) {
                        val obj = JSONObject().apply {
                            put("appName", app.appName)
                            put("packageName", app.packageName)
                            put("versionName", app.versionName)
                            put("apkSize", app.apkSize)
                            put("formattedSize", app.formattedSize)
                            put("isSystemApp", app.isSystemApp)
                            put("isCurrentApp", app.isCurrentApp)
                        }
                        jsonArray.put(obj)
                    }
                    sendJsonResponse(out, jsonArray)
                }

                // API: Make APK file by Android from installed app
                method == "POST" && path == "/api/make-apk" -> {
                    val body = readBody(reader, contentLength)
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val queryParams = parseQueryParams(query)
                    val packageName = json.optString("packageName", queryParams["packageName"] ?: "")
                    if (packageName.isNotBlank()) {
                        val createdFile = onMakeApkRequested?.invoke(packageName)
                        if (createdFile != null) {
                            onActivityLogged("Made APK file by Android: ${createdFile.name}", true)
                            val fileObj = JSONObject().apply {
                                put("id", createdFile.id)
                                put("name", createdFile.name)
                                put("formattedSize", createdFile.formattedSize)
                                put("downloadUrl", "/download/${createdFile.id}")
                            }
                            sendJsonResponse(
                                out,
                                JSONObject()
                                    .put("success", true)
                                    .put("message", "APK successfully made by Android!")
                                    .put("file", fileObj)
                            )
                        } else {
                            sendJsonResponse(
                                out,
                                JSONObject().put("success", false).put("error", "Failed to extract/make APK on device"),
                                500
                            )
                        }
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Missing packageName"), 400)
                    }
                }

                // API: Create custom APK archive package by Android
                method == "POST" && path == "/api/create-custom-apk" -> {
                    val body = readBody(reader, contentLength)
                    val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    val appName = json.optString("appName", "CustomApp")
                    val pkgName = json.optString("packageName", "com.example.${appName.lowercase().replace(Regex("[^a-z0-9]"), "")}")
                    val version = json.optString("version", "1.0")
                    val createdFile = onCreateCustomApkRequested?.invoke(appName, pkgName, version)
                    if (createdFile != null) {
                        onActivityLogged("Created custom APK by Android: ${createdFile.name}", true)
                        val fileObj = JSONObject().apply {
                            put("id", createdFile.id)
                            put("name", createdFile.name)
                            put("formattedSize", createdFile.formattedSize)
                            put("downloadUrl", "/download/${createdFile.id}")
                        }
                        sendJsonResponse(
                            out,
                            JSONObject()
                                .put("success", true)
                                .put("message", "Custom APK created by Android!")
                                .put("file", fileObj)
                        )
                    } else {
                        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Failed to create APK package"), 500)
                    }
                }

                // API: Upload file from Web to Phone
                method == "POST" && path == "/api/upload" -> {
                    handleUpload(rawInput, headers, out)
                }

                // Download or Preview file
                method == "GET" && (path.startsWith("/download/") || path.startsWith("/preview/")) -> {
                    val fileId = path.substringAfterLast("/")
                    val isPreview = path.startsWith("/preview/")
                    val file = getSharedFiles().find { it.id == fileId }
                    if (file?.file != null && file.file.exists()) {
                        downloadCount++
                        onActivityLogged("File ${if (isPreview) "previewed" else "downloaded"} from web: ${file.name}", true)
                        streamFile(out, file.file, file.name, file.mimeType, asAttachment = !isPreview)
                    } else {
                        sendResponse(out, 404, "Not Found", "text/plain", "File not found".toByteArray())
                    }
                }

                path == "/favicon.ico" -> {
                    sendResponse(out, 204, "No Content", "image/x-icon", ByteArray(0))
                }

                else -> {
                    sendResponse(out, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            // client disconnected or parsing error
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun handleUpload(rawInput: InputStream, headers: Map<String, String>, out: OutputStream) {
        val contentType = headers["content-type"] ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

        try {
            val receivedDir = File(context.filesDir, "received").apply { mkdirs() }

            if (contentType.contains("multipart/form-data")) {
                // Parse multipart boundary
                val boundary = contentType.substringAfter("boundary=").trim().removeSurrounding("\"")
                val filename = parseMultipartAndSave(rawInput, boundary, receivedDir, contentLength)
                if (filename != null) {
                    uploadCount++
                    val uploadedFile = File(receivedDir, filename)
                    onFileReceived(uploadedFile)
                    onActivityLogged("File uploaded from web: $filename (${uploadedFile.length() / 1024} KB)", true)
                    sendJsonResponse(out, JSONObject().put("success", true).put("fileName", filename))
                    return
                }
            } else {
                // Direct binary upload with X-File-Name header
                val clientFileName = headers["x-file-name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "uploaded_${System.currentTimeMillis()}.bin"
                val destFile = File(receivedDir, clientFileName)
                val fileOut = FileOutputStream(destFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var total = 0L
                while (total < contentLength) {
                    val toRead = Math.min(buffer.size.toLong(), contentLength - total).toInt()
                    bytesRead = rawInput.read(buffer, 0, toRead)
                    if (bytesRead == -1) break
                    fileOut.write(buffer, 0, bytesRead)
                    total += bytesRead
                }
                fileOut.flush()
                fileOut.close()

                uploadCount++
                onFileReceived(destFile)
                onActivityLogged("File uploaded from web: $clientFileName (${destFile.length() / 1024} KB)", true)
                sendJsonResponse(out, JSONObject().put("success", true).put("fileName", clientFileName))
                return
            }
        } catch (e: Exception) {
            onActivityLogged("File upload failed: ${e.message}", false)
        }
        sendJsonResponse(out, JSONObject().put("success", false).put("error", "Upload failed"), 500)
    }

    private fun parseMultipartAndSave(
        input: InputStream,
        boundary: String,
        targetDir: File,
        contentLength: Long
    ): String? {
        val boundaryBytes = ("--$boundary").toByteArray(StandardCharsets.UTF_8)
        var fileName: String? = null
        var tempFile: File? = null

        // Simple streaming parser
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.startsWith("--$boundary")) {
                // Header of part
                var partHeader: String?
                var isFilePart = false
                while (reader.readLine().also { partHeader = it } != null) {
                    if (partHeader.isNullOrBlank()) break
                    if (partHeader!!.contains("filename=", ignoreCase = true)) {
                        val fn = partHeader!!.substringAfter("filename=").substringBefore(";").trim().removeSurrounding("\"")
                        if (fn.isNotBlank()) {
                            fileName = File(fn).name
                            isFilePart = true
                        }
                    }
                }

                if (isFilePart && fileName != null) {
                    tempFile = File(targetDir, fileName)
                    val fos = FileOutputStream(tempFile)
                    val buffer = StringBuilder()
                    var charVal: Int
                    val endBoundary = "\r\n--$boundary"
                    while (reader.read().also { charVal = it } != -1) {
                        buffer.append(charVal.toChar())
                        if (buffer.endsWith(endBoundary) || buffer.endsWith("--$boundary")) {
                            val contentStr = buffer.substring(0, buffer.length - endBoundary.length)
                            val bytes = contentStr.toByteArray(StandardCharsets.ISO_8859_1)
                            fos.write(bytes)
                            break
                        }
                    }
                    fos.flush()
                    fos.close()
                    break
                }
            }
        }
        return if (tempFile?.exists() == true && tempFile.length() > 0) fileName else null
    }

    private fun streamFile(out: OutputStream, file: File, fileName: String, mimeType: String, asAttachment: Boolean) {
        val fileLength = file.length()
        val disposition = if (asAttachment) {
            "attachment; filename=\"${URLEncoder.encode(fileName, "UTF-8")}\""
        } else {
            "inline"
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mimeType\r\n" +
                "Content-Length: $fileLength\r\n" +
                "Content-Disposition: $disposition\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

        out.write(header.toByteArray(StandardCharsets.UTF_8))
        val fis = FileInputStream(file)
        val buf = ByteArray(16384)
        var read: Int
        while (fis.read(buf).also { read = it } != -1) {
            out.write(buf, 0, read)
        }
        fis.close()
        out.flush()
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val chars = CharArray(length)
        var total = 0
        while (total < length) {
            val r = reader.read(chars, total, length - total)
            if (r == -1) break
            total += r
        }
        return String(chars, 0, total)
    }

    private fun sendJsonResponse(out: OutputStream, json: JSONObject, statusCode: Int = 200) {
        val statusText = if (statusCode == 200) "OK" else "Error"
        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
        sendResponse(out, statusCode, statusText, "application/json; charset=UTF-8", data)
    }

    private fun sendJsonResponse(out: OutputStream, json: JSONArray, statusCode: Int = 200) {
        val statusText = if (statusCode == 200) "OK" else "Error"
        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
        sendResponse(out, statusCode, statusText, "application/json; charset=UTF-8", data)
    }

    private fun sendResponse(out: OutputStream, statusCode: Int, statusText: String, contentType: String, data: ByteArray) {
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-File-Name\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun buildHtmlPage(deviceName: String): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Web Share My File</title>
    <style>
        :root {
            --primary: #00B4D8;
            --primary-dark: #0077B6;
            --primary-light: #90E0EF;
            --bg-dark: #0B132B;
            --card-bg: #1C2541;
            --card-hover: #273456;
            --surface: #141E38;
            --text-main: #FFFFFF;
            --text-muted: #8D99AE;
            --success: #10B981;
            --warning: #F59E0B;
            --border: #2D3A5D;
            --danger: #EF4444;
        }

        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }

        body {
            background-color: var(--bg-dark);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }

        header {
            background: rgba(28, 37, 65, 0.95);
            backdrop-filter: blur(12px);
            border-bottom: 1px solid var(--border);
            padding: 16px 24px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            position: sticky;
            top: 0;
            z-index: 100;
        }

        .brand-box {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .brand-icon {
            width: 40px;
            height: 40px;
            background: linear-gradient(135deg, var(--primary), var(--primary-dark));
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 12px rgba(0, 180, 216, 0.3);
        }

        .brand-title {
            font-size: 1.25rem;
            font-weight: 700;
            letter-spacing: -0.5px;
        }

        .device-badge-wrap {
            display: flex;
            align-items: center;
            background: var(--surface);
            border: 1px solid var(--border);
            padding: 6px 14px;
            border-radius: 20px;
            gap: 8px;
            font-size: 0.9rem;
        }

        .device-name-text {
            color: var(--primary-light);
            font-weight: 600;
        }

        .edit-device-btn {
            background: transparent;
            border: none;
            color: var(--text-muted);
            cursor: pointer;
            padding: 2px;
            display: flex;
            align-items: center;
            transition: color 0.2s;
        }

        .edit-device-btn:hover {
            color: var(--primary);
        }

        .live-dot {
            width: 8px;
            height: 8px;
            background-color: var(--success);
            border-radius: 50%;
            box-shadow: 0 0 8px var(--success);
            animation: pulse 2s infinite;
        }

        @keyframes pulse {
            0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
            70% { transform: scale(1.1); box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
            100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
        }

        main {
            flex: 1;
            max-width: 1200px;
            width: 100%;
            margin: 0 auto;
            padding: 24px 20px 48px;
            display: flex;
            flex-direction: column;
            gap: 24px;
        }

        /* Hero Upload Box */
        .upload-card {
            background: linear-gradient(145deg, #1C2541, #131B32);
            border: 2px dashed var(--border);
            border-radius: 16px;
            padding: 32px 24px;
            text-align: center;
            cursor: pointer;
            transition: all 0.25s ease;
            position: relative;
        }

        .upload-card:hover, .upload-card.dragover {
            border-color: var(--primary);
            background: rgba(0, 180, 216, 0.05);
            transform: translateY(-2px);
        }

        .upload-icon {
            width: 52px;
            height: 52px;
            margin: 0 auto 12px;
            fill: var(--primary);
        }

        .upload-title {
            font-size: 1.15rem;
            font-weight: 600;
            margin-bottom: 6px;
        }

        .upload-subtitle {
            font-size: 0.9rem;
            color: var(--text-muted);
        }

        /* Tabs & Controls */
        .controls-bar {
            display: flex;
            flex-wrap: wrap;
            align-items: center;
            justify-content: space-between;
            gap: 16px;
        }

        .nav-tabs {
            display: flex;
            gap: 8px;
            overflow-x: auto;
            padding-bottom: 4px;
        }

        .tab-btn {
            background: var(--surface);
            color: var(--text-muted);
            border: 1px solid var(--border);
            padding: 8px 18px;
            border-radius: 20px;
            cursor: pointer;
            font-size: 0.9rem;
            font-weight: 500;
            transition: all 0.2s;
            white-space: nowrap;
        }

        .tab-btn.active, .tab-btn:hover {
            background: var(--primary);
            color: #0B132B;
            border-color: var(--primary);
            font-weight: 600;
        }

        .search-wrap {
            position: relative;
            min-width: 240px;
        }

        .search-input {
            width: 100%;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 20px;
            padding: 8px 16px 8px 36px;
            color: white;
            font-size: 0.9rem;
            outline: none;
            transition: border-color 0.2s;
        }

        .search-input:focus {
            border-color: var(--primary);
        }

        .search-icon {
            position: absolute;
            left: 12px;
            top: 50%;
            transform: translateY(-50%);
            fill: var(--text-muted);
            width: 16px;
            height: 16px;
        }

        /* File Grid */
        .file-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 16px;
        }

        .file-card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            gap: 12px;
            transition: all 0.2s;
        }

        .file-card:hover {
            background: var(--card-hover);
            border-color: rgba(0, 180, 216, 0.4);
            transform: translateY(-3px);
            box-shadow: 0 8px 20px rgba(0, 0, 0, 0.3);
        }

        .file-header {
            display: flex;
            align-items: flex-start;
            gap: 12px;
        }

        .file-badge {
            width: 44px;
            height: 44px;
            border-radius: 10px;
            background: rgba(0, 180, 216, 0.15);
            color: var(--primary-light);
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 0.85rem;
            flex-shrink: 0;
            text-transform: uppercase;
        }

        .file-info {
            flex: 1;
            min-width: 0;
        }

        .file-name {
            font-size: 0.95rem;
            font-weight: 600;
            color: var(--text-main);
            word-break: break-word;
            margin-bottom: 4px;
            display: -webkit-box;
            -webkit-line-clamp: 2;
            -webkit-box-orient: vertical;
            overflow: hidden;
        }

        .file-meta {
            font-size: 0.8rem;
            color: var(--text-muted);
        }

        .file-actions {
            display: flex;
            gap: 8px;
            border-top: 1px solid rgba(255, 255, 255, 0.06);
            padding-top: 12px;
        }

        .btn-action {
            flex: 1;
            padding: 7px 12px;
            border-radius: 8px;
            font-size: 0.85rem;
            font-weight: 500;
            cursor: pointer;
            border: none;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
            transition: opacity 0.2s;
        }

        .btn-action:hover {
            opacity: 0.88;
        }

        .btn-download {
            background: var(--primary);
            color: #0B132B;
            font-weight: 600;
            text-decoration: none;
        }

        .btn-rename {
            background: var(--surface);
            color: var(--primary-light);
            border: 1px solid var(--border);
        }

        .btn-install {
            background: #10B981;
            color: #0B132B;
            font-weight: 600;
        }

        .btn-install:hover {
            background: #34D399;
        }

        .app-badge {
            display: inline-block;
            background: rgba(16, 185, 129, 0.2);
            color: #10B981;
            font-weight: 700;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 0.72rem;
            margin-left: 6px;
            vertical-align: middle;
        }

        /* Message / Chat Section */
        .message-section {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 20px;
        }

        .section-title {
            font-size: 1.1rem;
            font-weight: 600;
            margin-bottom: 14px;
            display: flex;
            align-items: center;
            gap: 8px;
        }

        .msg-history {
            max-height: 220px;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 10px;
            margin-bottom: 14px;
            padding-right: 4px;
        }

        .msg-bubble {
            padding: 10px 14px;
            border-radius: 12px;
            max-width: 80%;
            font-size: 0.9rem;
            word-break: break-word;
            position: relative;
        }

        .msg-phone {
            background: #23345A;
            align-self: flex-start;
            border-bottom-left-radius: 2px;
        }

        .msg-web {
            background: var(--primary-dark);
            align-self: flex-end;
            border-bottom-right-radius: 2px;
        }

        .msg-sender {
            font-size: 0.72rem;
            color: var(--text-muted);
            margin-bottom: 2px;
        }

        .msg-input-wrap {
            display: flex;
            gap: 10px;
        }

        .msg-input {
            flex: 1;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 10px 14px;
            color: white;
            font-size: 0.9rem;
            outline: none;
        }

        .btn-send-msg {
            background: var(--primary);
            color: #0B132B;
            font-weight: 600;
            padding: 10px 20px;
            border: none;
            border-radius: 10px;
            cursor: pointer;
        }

        /* Modal Dialog */
        .modal-overlay {
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(4px);
            z-index: 999;
            align-items: center;
            justify-content: center;
            padding: 16px;
        }

        .modal-card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            max-width: 440px;
            width: 100%;
            padding: 24px;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
        }

        .modal-title {
            font-size: 1.15rem;
            font-weight: 700;
            margin-bottom: 16px;
        }

        .modal-input {
            width: 100%;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 12px 14px;
            color: white;
            font-size: 0.95rem;
            margin-bottom: 20px;
            outline: none;
        }

        .modal-input:focus {
            border-color: var(--primary);
        }

        .modal-buttons {
            display: flex;
            justify-content: flex-end;
            gap: 10px;
        }

        .btn-cancel {
            background: transparent;
            border: 1px solid var(--border);
            color: var(--text-muted);
            padding: 8px 16px;
            border-radius: 8px;
            cursor: pointer;
        }

        .btn-confirm {
            background: var(--primary);
            color: #0B132B;
            font-weight: 600;
            border: none;
            padding: 8px 20px;
            border-radius: 8px;
            cursor: pointer;
        }

        .empty-state {
            grid-column: 1 / -1;
            padding: 60px 20px;
            text-align: center;
            color: var(--text-muted);
        }

        #uploadProgress {
            display: none;
            margin-top: 14px;
            height: 6px;
            background: var(--border);
            border-radius: 3px;
            overflow: hidden;
        }

        #progressBar {
            height: 100%;
            width: 0%;
            background: var(--primary);
            transition: width 0.2s;
        }

        footer {
            text-align: center;
            padding: 16px;
            font-size: 0.8rem;
            color: var(--text-muted);
            border-top: 1px solid rgba(255, 255, 255, 0.05);
        }
    </style>
</head>
<body>

    <header>
        <div class="brand-box">
            <div class="brand-icon">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="#0B132B">
                    <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14.5v-9l6 4.5-6 4.5z"/>
                </svg>
            </div>
            <div>
                <div class="brand-title">Web Share My File</div>
            </div>
        </div>

        <div class="device-badge-wrap">
            <span class="live-dot"></span>
            <span>Station:</span>
            <span id="stationName" class="device-name-text">$deviceName</span>
            <button class="edit-device-btn" onclick="openDeviceRenameModal()" title="Change station name">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/>
                </svg>
            </button>
        </div>
    </header>

    <main>
        <!-- Upload Card -->
        <div class="upload-card" id="dropArea" onclick="document.getElementById('fileInput').click()">
            <input type="file" id="fileInput" multiple style="display:none" onchange="uploadSelectedFiles(this.files)">
            <svg class="upload-icon" viewBox="0 0 24 24">
                <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
            </svg>
            <div class="upload-title">Click or Drag Files Here to Send to Phone</div>
            <div class="upload-subtitle">Upload files or Android APKs directly to install apps wirelessly on mobile</div>
            <div id="uploadProgress"><div id="progressBar"></div></div>
        </div>

        <!-- Controls Bar -->
        <div class="controls-bar">
            <div class="nav-tabs" id="categoryTabs">
                <button class="tab-btn active" onclick="setCategory('ALL')">All (<span id="countAll">0</span>)</button>
                <button class="tab-btn" onclick="setCategory('APPS')">Apps (APKs)</button>
                <button class="tab-btn" onclick="setCategory('DOCUMENT')">Docs</button>
                <button class="tab-btn" onclick="setCategory('IMAGE')">Photos</button>
                <button class="tab-btn" onclick="setCategory('VIDEO')">Videos</button>
                <button class="tab-btn" onclick="setCategory('AUDIO')">Audio</button>
                <button class="tab-btn" onclick="setCategory('RECEIVED')">Received</button>
                <button class="tab-btn" onclick="openMakeApkModal()" style="border: 1px solid #10B981; color: #10B981; font-weight: 700; background: rgba(16, 185, 129, 0.1);">📦 Make APK by Android</button>
            </div>

            <div class="search-wrap">
                <svg class="search-icon" viewBox="0 0 24 24">
                    <path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/>
                </svg>
                <input type="text" id="searchInput" class="search-input" placeholder="Search shared files..." oninput="renderFiles()">
            </div>
        </div>

        <!-- File Grid -->
        <div class="file-grid" id="fileGrid">
            <div class="empty-state">Loading shared files...</div>
        </div>

        <!-- Live Messaging Section -->
        <div class="message-section">
            <div class="section-title">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="var(--primary)">
                    <path d="M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z"/>
                </svg>
                <span>Live Messages & Clipboard</span>
            </div>
            <div class="msg-history" id="msgHistory">
                <div style="color:var(--text-muted); font-size:0.85rem;">No messages exchanged yet. Send a note to the phone below.</div>
            </div>
            <div class="msg-input-wrap">
                <input type="text" id="msgInput" class="msg-input" placeholder="Type text or link to send to phone..." onkeydown="if(event.key==='Enter') sendTextMessage()">
                <button class="btn-send-msg" onclick="sendTextMessage()">Send to Phone</button>
            </div>
        </div>
    </main>

    <!-- Modal: Rename File -->
    <div class="modal-overlay" id="renameModal">
        <div class="modal-card">
            <div class="modal-title">Rename File on Phone</div>
            <input type="hidden" id="renameFileId">
            <input type="text" id="renameFileInput" class="modal-input" placeholder="Enter new file name">
            <div class="modal-buttons">
                <button class="btn-cancel" onclick="closeRenameModal()">Cancel</button>
                <button class="btn-confirm" onclick="submitRenameFile()">Save Name</button>
            </div>
        </div>
    </div>

    <!-- Modal: Rename Device / Station -->
    <div class="modal-overlay" id="deviceModal">
        <div class="modal-card">
            <div class="modal-title">Change Station Name</div>
            <input type="text" id="deviceNameInput" class="modal-input" placeholder="Enter new station name">
            <div class="modal-buttons">
                <button class="btn-cancel" onclick="closeDeviceModal()">Cancel</button>
                <button class="btn-confirm" onclick="submitDeviceRename()">Save Name</button>
            </div>
        </div>
    </div>

    <!-- Modal: Make APK by Android -->
    <div class="modal-overlay" id="makeApkModal">
        <div class="modal-card" style="max-width: 520px; max-height: 85vh; display: flex; flex-direction: column;">
            <div class="modal-title" style="display:flex; align-items:center; justify-content:space-between; margin-bottom: 8px;">
                <span>📦 Make APK File by Android</span>
                <button onclick="closeMakeApkModal()" style="background:none; border:none; color:var(--text-muted); font-size:1.3rem; cursor:pointer;">&times;</button>
            </div>
            <div style="font-size:0.85rem; color:var(--text-muted); margin-bottom:12px;">
                Make or extract standalone APK installer files from Android applications installed on the phone.
            </div>
            <input type="text" id="apkSearchInput" class="modal-input" placeholder="Search phone apps to make APK..." style="margin-bottom:12px;" oninput="filterInstalledApps()">
            <div id="installedAppsContainer" style="flex:1; overflow-y:auto; max-height: 300px; display:flex; flex-direction:column; gap:8px; padding-right:4px;">
                <div style="text-align:center; padding:20px; color:var(--text-muted);">Loading apps from phone...</div>
            </div>
            <div class="modal-buttons" style="margin-top:14px; border-top:1px solid var(--border); padding-top:12px;">
                <button class="btn-cancel" onclick="closeMakeApkModal()">Close</button>
                <button class="btn-confirm" onclick="openCustomApkModal()" style="background:#10B981; color:#0B132B;">+ Create Blank APK</button>
            </div>
        </div>
    </div>

    <!-- Modal: Create Custom APK Package -->
    <div class="modal-overlay" id="customApkModal">
        <div class="modal-card">
            <div class="modal-title">Make Custom APK by Android</div>
            <label style="font-size:0.8rem; color:var(--text-muted); margin-bottom:4px; display:block;">Application Name</label>
            <input type="text" id="customApkName" class="modal-input" placeholder="e.g. MyMobileTool">
            <label style="font-size:0.8rem; color:var(--text-muted); margin-bottom:4px; display:block;">Package Name</label>
            <input type="text" id="customApkPkg" class="modal-input" placeholder="com.example.mymobiletool">
            <label style="font-size:0.8rem; color:var(--text-muted); margin-bottom:4px; display:block;">Version</label>
            <input type="text" id="customApkVersion" class="modal-input" value="1.0" placeholder="1.0">
            <div class="modal-buttons">
                <button class="btn-cancel" onclick="closeCustomApkModal()">Cancel</button>
                <button class="btn-confirm" onclick="submitCreateCustomApk()" style="background:#10B981; color:#0B132B;">Make APK File</button>
            </div>
        </div>
    </div>

    <footer>
        Web Share My File &bull; Wireless Local Network Transfer
    </footer>

    <script>
        let allFiles = [];
        let currentCategory = 'ALL';

        async function loadFiles() {
            try {
                const res = await fetch('/api/files');
                allFiles = await res.json();
                document.getElementById('countAll').innerText = allFiles.length;
                renderFiles();
            } catch (e) {
                console.error("Failed to load files", e);
            }
        }

        async function loadStatus() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();
                if (data.deviceName) {
                    document.getElementById('stationName').innerText = data.deviceName;
                }
            } catch (e) {}
        }

        async function loadMessages() {
            try {
                const res = await fetch('/api/messages');
                const msgs = await res.json();
                const container = document.getElementById('msgHistory');
                if (msgs.length === 0) {
                    container.innerHTML = '<div style="color:var(--text-muted); font-size:0.85rem;">No messages exchanged yet. Send a note to the phone below.</div>';
                    return;
                }
                container.innerHTML = '';
                msgs.forEach(function(m) {
                    var bubble = document.createElement('div');
                    bubble.className = 'msg-bubble ' + (m.isFromPhone ? 'msg-phone' : 'msg-web');
                    bubble.innerHTML = '<div class="msg-sender">' + escapeHtml(m.sender) + '</div><div>' + escapeHtml(m.text) + '</div>';
                    container.appendChild(bubble);
                });
                container.scrollTop = container.scrollHeight;
            } catch (e) {}
        }

        function setCategory(cat) {
            currentCategory = cat;
            document.querySelectorAll('#categoryTabs .tab-btn').forEach(function(btn) {
                btn.classList.remove('active');
            });
            event.target.classList.add('active');
            renderFiles();
        }

        function renderFiles() {
            var query = document.getElementById('searchInput').value.toLowerCase().trim();
            var grid = document.getElementById('fileGrid');

            var filtered = allFiles.filter(function(f) {
                var matchCategory = currentCategory === 'ALL' || f.category === currentCategory;
                var matchQuery = !query || f.name.toLowerCase().indexOf(query) !== -1;
                return matchCategory && matchQuery;
            });

            if (filtered.length === 0) {
                grid.innerHTML = '<div class="empty-state">No files found matching your criteria.</div>';
                return;
            }

            grid.innerHTML = '';
            filtered.forEach(function(f) {
                var card = document.createElement('div');
                card.className = 'file-card';
                var extLabel = f.extension || 'FILE';
                var safeName = escapeHtml(f.name);
                var safeId = f.id;
                var isApk = f.isApk || f.category === 'APPS' || extLabel === 'APK';
                var badgeStyle = isApk ? ' style="background:rgba(16,185,129,0.2);color:#10B981;"' : '';
                var safeMeta = f.formattedSize + ' &bull; ' + (isApk ? 'App (APK)' : f.category);
                var installBtn = isApk ? ('<button class="btn-action btn-install" onclick="requestAppInstall(\'' + safeId + '\', \'' + safeName.replace(/\'/g, "\\'") + '\')">' +
                    '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/></svg> Install on Mobile' +
                '</button>') : '';

                card.innerHTML = '<div class="file-header">' +
                    '<div class="file-badge"' + badgeStyle + '>' + extLabel + '</div>' +
                    '<div class="file-info">' +
                        '<div class="file-name" title="' + safeName + '">' + safeName + (isApk ? ' <span class="app-badge">APK</span>' : '') + '</div>' +
                        '<div class="file-meta">' + safeMeta + '</div>' +
                    '</div>' +
                '</div>' +
                '<div class="file-actions">' +
                    installBtn +
                    '<a href="/download/' + safeId + '" class="btn-action btn-download" download>' +
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg> Download' +
                    '</a>' +
                    '<button class="btn-action btn-rename" onclick="openRenameModal(\'' + safeId + '\', \'' + safeName.replace(/\'/g, "\\'") + '\')">' +
                        '<svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg> Rename' +
                    '</button>' +
                '</div>';
                grid.appendChild(card);
            });
        }

        // Request install app on mobile device
        function requestAppInstall(fileId, fileName) {
            if (!confirm('Send install prompt to mobile phone for ' + fileName + '?')) return;
            fetch('/api/install-app', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ fileId: fileId })
            })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (data.success) {
                    alert('Install request sent to phone! Please check your mobile screen to finish installation.');
                } else {
                    alert('Could not start install on mobile: ' + (data.error || 'Unknown error'));
                }
            })
            .catch(function(err) {
                alert('Connection error communicating with phone.');
            });
        }

        // Rename file on phone from web
        function openRenameModal(fileId, currentName) {
            document.getElementById('renameFileId').value = fileId;
            document.getElementById('renameFileInput').value = currentName;
            document.getElementById('renameModal').style.display = 'flex';
            document.getElementById('renameFileInput').focus();
        }

        function closeRenameModal() {
            document.getElementById('renameModal').style.display = 'none';
        }

        async function submitRenameFile() {
            const fileId = document.getElementById('renameFileId').value;
            const newName = document.getElementById('renameFileInput').value.trim();
            if (!newName) return;

            try {
                const res = await fetch('/api/rename', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ id: fileId, newName: newName })
                });
                const result = await res.json();
                if (result.success) {
                    closeRenameModal();
                    loadFiles();
                } else {
                    alert(result.error || 'Failed to rename file');
                }
            } catch (e) {
                alert('Network error while renaming file');
            }
        }

        // Change Station / Device name from web
        function openDeviceRenameModal() {
            document.getElementById('deviceNameInput').value = document.getElementById('stationName').innerText;
            document.getElementById('deviceModal').style.display = 'flex';
            document.getElementById('deviceNameInput').focus();
        }

        function closeDeviceModal() {
            document.getElementById('deviceModal').style.display = 'none';
        }

        async function submitDeviceRename() {
            const newName = document.getElementById('deviceNameInput').value.trim();
            if (!newName) return;
            try {
                const res = await fetch('/api/device-name', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ name: newName })
                });
                const result = await res.json();
                if (result.success) {
                    document.getElementById('stationName').innerText = newName;
                    closeDeviceModal();
                }
            } catch (e) {
                alert('Failed to update station name');
            }
        }

        // Send text message from web to phone
        async function sendTextMessage() {
            const input = document.getElementById('msgInput');
            const text = input.value.trim();
            if (!text) return;
            try {
                await fetch('/api/message', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ text: text })
                });
                input.value = '';
                loadMessages();
            } catch (e) {}
        }

        // Upload files
        async function uploadSelectedFiles(files) {
            if (!files || files.length === 0) return;
            const progressWrap = document.getElementById('uploadProgress');
            const bar = document.getElementById('progressBar');
            progressWrap.style.display = 'block';

            var hasApk = false;
            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                if (file.name.toLowerCase().endsWith('.apk')) {
                    hasApk = true;
                }
                bar.style.width = Math.round(((i) / files.length) * 100) + '%';
                try {
                    await fetch('/api/upload', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/octet-stream',
                            'X-File-Name': encodeURIComponent(file.name)
                        },
                        body: file
                    });
                } catch (e) {
                    console.error('Upload error for ' + file.name, e);
                }
            }
            bar.style.width = '100%';
            setTimeout(() => {
                progressWrap.style.display = 'none';
                bar.style.width = '0%';
                loadFiles();
                if (hasApk) {
                    alert('Android APK sent to mobile! You can now tap "Install on Mobile" on the web page or confirm on the mobile device to install.');
                }
            }, 600);
        }

        // Drag and drop setup
        const dropArea = document.getElementById('dropArea');
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            dropArea.addEventListener(eventName, e => {
                e.preventDefault();
                e.stopPropagation();
            }, false);
        });
        ['dragenter', 'dragover'].forEach(eventName => {
            dropArea.addEventListener(eventName, () => dropArea.classList.add('dragover'), false);
        });
        ['dragleave', 'drop'].forEach(eventName => {
            dropArea.addEventListener(eventName, () => dropArea.classList.remove('dragover'), false);
        });
        dropArea.addEventListener('drop', e => {
            const dt = e.dataTransfer;
            if (dt && dt.files) {
                uploadSelectedFiles(dt.files);
            }
        });

        let installedApps = [];

        function openMakeApkModal() {
            document.getElementById('makeApkModal').style.display = 'flex';
            loadInstalledApps();
        }

        function closeMakeApkModal() {
            document.getElementById('makeApkModal').style.display = 'none';
        }

        async function loadInstalledApps() {
            const container = document.getElementById('installedAppsContainer');
            container.innerHTML = '<div style="text-align:center; padding:20px; color:var(--text-muted);">Scanning installed applications on phone...</div>';
            try {
                const res = await fetch('/api/installed-apps');
                installedApps = await res.json();
                renderInstalledApps(installedApps);
            } catch (e) {
                container.innerHTML = '<div style="text-align:center; padding:20px; color:#EF4444;">Failed to load installed apps from phone.</div>';
            }
        }

        function renderInstalledApps(apps) {
            const container = document.getElementById('installedAppsContainer');
            if (!apps || apps.length === 0) {
                container.innerHTML = '<div style="text-align:center; padding:20px; color:var(--text-muted);">No installed apps found.</div>';
                return;
            }
            container.innerHTML = '';
            apps.forEach(app => {
                const item = document.createElement('div');
                item.style.cssText = 'display:flex; align-items:center; justify-content:space-between; background:var(--surface); border:1px solid var(--border); border-radius:10px; padding:10px 14px; gap:10px;';
                
                const isCurrent = app.isCurrentApp;
                const badge = isCurrent ? ' <span style="background:rgba(0,180,216,0.2); color:var(--primary); font-size:0.7rem; padding:2px 6px; border-radius:4px; font-weight:bold;">This App</span>' : '';

                item.innerHTML = '<div>' +
                    '<div style="font-weight:600; font-size:0.92rem; color:var(--text-main);">' + escapeHtml(app.appName) + badge + '</div>' +
                    '<div style="font-size:0.75rem; color:var(--text-muted);">' + escapeHtml(app.packageName) + ' &bull; v' + escapeHtml(app.versionName) + ' (' + escapeHtml(app.formattedSize) + ')</div>' +
                '</div>' +
                '<button onclick="requestMakeApk(\'' + escapeHtml(app.packageName) + '\', \'' + escapeHtml(app.appName).replace(/\'/g, "\\'") + '\')" style="background:#10B981; color:#0B132B; font-weight:700; border:none; border-radius:6px; padding:6px 12px; font-size:0.8rem; cursor:pointer; flex-shrink:0;">' +
                    'Make APK' +
                '</button>';
                container.appendChild(item);
            });
        }

        function filterInstalledApps() {
            const query = document.getElementById('apkSearchInput').value.toLowerCase().trim();
            if (!query) {
                renderInstalledApps(installedApps);
                return;
            }
            const filtered = installedApps.filter(a => a.appName.toLowerCase().indexOf(query) !== -1 || a.packageName.toLowerCase().indexOf(query) !== -1);
            renderInstalledApps(filtered);
        }

        async function requestMakeApk(pkgName, appName) {
            if (!confirm('Make standalone APK file from "' + appName + '" on the phone?')) return;
            try {
                const res = await fetch('/api/make-apk', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ packageName: pkgName })
                });
                const result = await res.json();
                if (result.success) {
                    alert('APK created successfully: ' + result.file.name + '!\nIt is now ready in your files and can be downloaded.');
                    closeMakeApkModal();
                    loadFiles();
                    window.location.href = result.file.downloadUrl;
                } else {
                    alert('Error making APK: ' + (result.error || 'Unknown error'));
                }
            } catch (e) {
                alert('Network error communicating with phone to make APK.');
            }
        }

        function openCustomApkModal() {
            closeMakeApkModal();
            document.getElementById('customApkModal').style.display = 'flex';
        }

        function closeCustomApkModal() {
            document.getElementById('customApkModal').style.display = 'none';
        }

        async function submitCreateCustomApk() {
            const appName = document.getElementById('customApkName').value.trim() || 'CustomApp';
            const pkgName = document.getElementById('customApkPkg').value.trim() || ('com.example.' + appName.toLowerCase().replace(/[^a-z0-9]/g, ''));
            const version = document.getElementById('customApkVersion').value.trim() || '1.0';

            try {
                const res = await fetch('/api/create-custom-apk', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ appName: appName, packageName: pkgName, version: version })
                });
                const result = await res.json();
                if (result.success) {
                    alert('Custom APK file created: ' + result.file.name);
                    closeCustomApkModal();
                    loadFiles();
                    window.location.href = result.file.downloadUrl;
                } else {
                    alert('Failed to create APK: ' + (result.error || 'Unknown error'));
                }
            } catch (e) {
                alert('Network error creating custom APK.');
            }
        }

        function escapeHtml(str) {
            if (!str) return '';
            return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
        }

        // Initialize and poll updates
        loadFiles();
        loadStatus();
        loadMessages();
        setInterval(() => {
            loadFiles();
            loadMessages();
            loadStatus();
        }, 4000);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
