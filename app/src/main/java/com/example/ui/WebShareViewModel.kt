package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ActivityLog
import com.example.model.FileCategory
import com.example.model.ServerStatus
import com.example.model.SharedFile
import com.example.model.WebMessage
import com.example.server.WebShareServer
import com.example.util.AppInstallHelper
import com.example.util.FileHelper
import com.example.util.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class WebShareViewModel(application: Application) : AndroidViewModel(application) {

    private val _serverStatus = MutableStateFlow(
        ServerStatus(
            isRunning = false,
            ipAddress = "",
            port = 8080,
            deviceName = "Android WebStation"
        )
    )
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()

    private val _selectedCategory = MutableStateFlow(FileCategory.ALL)
    val selectedCategory: StateFlow<FileCategory> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _messages = MutableStateFlow<List<WebMessage>>(emptyList())
    val messages: StateFlow<List<WebMessage>> = _messages.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLog>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLog>> = _activityLogs.asStateFlow()

    private val _installPromptFile = MutableStateFlow<SharedFile?>(null)
    val installPromptFile: StateFlow<SharedFile?> = _installPromptFile.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isScanningApps = MutableStateFlow(false)
    val isScanningApps: StateFlow<Boolean> = _isScanningApps.asStateFlow()

    private var server: WebShareServer? = null

    init {
        // Load initial sample files so user has instant testable data
        viewModelScope.launch(Dispatchers.IO) {
            val samples = FileHelper.createSampleFiles(getApplication())
            _sharedFiles.value = samples
            logActivity("Loaded ${samples.size} initial files for sharing", true)

            // Auto-start server on launch for convenience
            startServer(8080)
        }
    }

    fun startServer(port: Int = _serverStatus.value.port) {
        viewModelScope.launch(Dispatchers.IO) {
            server?.stop()
            val newServer = WebShareServer(
                context = getApplication(),
                port = port,
                onFileReceived = { receivedFile ->
                    onFileReceivedFromWeb(receivedFile)
                },
                onFileRenamed = { id, newName ->
                    renameFileInternal(id, newName)
                },
                onMessageReceived = { msg ->
                    _messages.update { it + msg }
                },
                onDeviceNameChanged = { newName ->
                    _serverStatus.update { it.copy(deviceName = newName) }
                },
                getSharedFiles = { _sharedFiles.value },
                getMessages = { _messages.value },
                getDeviceName = { _serverStatus.value.deviceName },
                onActivityLogged = { msg, isSuccess ->
                    logActivity(msg, isSuccess)
                },
                onInstallRequested = { fileId ->
                    handleInstallRequest(fileId)
                },
                getInstalledApps = {
                    AppInstallHelper.getInstalledAppsList(getApplication())
                },
                onMakeApkRequested = { pkgName ->
                    val result = AppInstallHelper.makeApkFromApp(getApplication(), pkgName)
                    if (result.isSuccess) {
                        val file = result.getOrThrow()
                        _sharedFiles.update { listOf(file) + it }
                        _selectedCategory.value = FileCategory.APPS
                        logActivity("Made APK file by Android: ${file.name}", true)
                        file
                    } else {
                        logActivity("Failed to make APK by Android: ${result.exceptionOrNull()?.message}", false)
                        null
                    }
                },
                onCreateCustomApkRequested = { appName, pkgName, ver ->
                    val result = AppInstallHelper.createCustomApkPackage(getApplication(), appName, pkgName, ver)
                    if (result.isSuccess) {
                        val file = result.getOrThrow()
                        _sharedFiles.update { listOf(file) + it }
                        _selectedCategory.value = FileCategory.APPS
                        logActivity("Created custom APK package: ${file.name}", true)
                        file
                    } else {
                        logActivity("Failed to create custom APK: ${result.exceptionOrNull()?.message}", false)
                        null
                    }
                }
            )

            val success = newServer.start()
            if (success) {
                server = newServer
                val ip = newServer.getLocalIpAddress()
                _serverStatus.update {
                    it.copy(
                        isRunning = true,
                        ipAddress = ip,
                        port = port
                    )
                }
            } else {
                _serverStatus.update { it.copy(isRunning = false) }
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            server?.stop()
            server = null
            _serverStatus.update { it.copy(isRunning = false) }
        }
    }

    fun toggleServer() {
        if (_serverStatus.value.isRunning) {
            stopServer()
        } else {
            startServer(_serverStatus.value.port)
        }
    }

    fun updateDeviceName(newName: String) {
        if (newName.isBlank()) return
        _serverStatus.update { it.copy(deviceName = newName.trim()) }
        logActivity("Device sharing name set to '${newName.trim()}'", true)
    }

    fun renameFile(fileId: String, newName: String): Boolean {
        return renameFileInternal(fileId, newName)
    }

    private fun renameFileInternal(fileId: String, newName: String): Boolean {
        var success = false
        _sharedFiles.update { currentList ->
            currentList.map { item ->
                if (item.id == fileId) {
                    val trimmed = newName.trim()
                    var finalName = trimmed
                    // Ensure extension is retained if not provided
                    val oldExt = item.name.substringAfterLast('.', "")
                    if (oldExt.isNotEmpty() && !finalName.contains('.')) {
                        finalName = "$finalName.$oldExt"
                    }

                    // Rename on physical disk if file exists
                    val renamedFile = if (item.file != null && item.file.exists()) {
                        val newFile = File(item.file.parentFile, finalName)
                        if (item.file.renameTo(newFile)) {
                            newFile
                        } else {
                            item.file
                        }
                    } else null

                    success = true
                    logActivity("Renamed '${item.name}' to '$finalName'", true)
                    item.copy(
                        name = finalName,
                        file = renamedFile ?: item.file,
                        dateModified = System.currentTimeMillis()
                    )
                } else {
                    item
                }
            }
        }
        return success
    }

    fun removeFile(fileId: String) {
        _sharedFiles.update { current ->
            val item = current.find { it.id == fileId }
            if (item != null) {
                logActivity("Removed '${item.name}' from sharing list", true)
            }
            current.filterNot { it.id == fileId }
        }
    }

    fun addPickedFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val added = mutableListOf<SharedFile>()
            for (uri in uris) {
                val sharedFile = FileHelper.copyUriToAppStorage(context, uri)
                if (sharedFile != null) {
                    added.add(sharedFile)
                }
            }
            if (added.isNotEmpty()) {
                _sharedFiles.update { current -> added + current }
                logActivity("Added ${added.size} file(s) to share", true)
            }
        }
    }

    private fun onFileReceivedFromWeb(file: File) {
        val ext = file.name.substringAfterLast('.', "").uppercase()
        val mime = FileHelper.getMimeType(file.name)
        val isApk = ext == "APK" || mime == "application/vnd.android.package-archive"
        val category = if (isApk) FileCategory.APPS else FileCategory.RECEIVED
        val sharedFile = SharedFile(
            id = UUID.randomUUID().toString(),
            name = file.name,
            size = file.length(),
            mimeType = mime,
            category = category,
            file = file,
            dateModified = file.lastModified(),
            isReceivedFromWeb = true
        )
        _sharedFiles.update { listOf(sharedFile) + it }
        _serverStatus.update { it.copy(totalUploads = it.totalUploads + 1) }

        if (isApk) {
            logActivity("Received Android APK from web: '${file.name}'. Prompting install on mobile!", true)
            _installPromptFile.value = sharedFile
        } else {
            logActivity("File uploaded from web: ${file.name} (${file.length() / 1024} KB)", true)
        }
    }

    fun promptInstall(file: SharedFile) {
        _installPromptFile.value = file
    }

    fun dismissInstallPrompt() {
        _installPromptFile.value = null
    }

    fun installAppDirectly(file: SharedFile) {
        val targetFile = file.file
        if (targetFile != null && targetFile.exists()) {
            val result = AppInstallHelper.installApk(getApplication(), targetFile)
            if (result.isSuccess) {
                logActivity("Package installer launched for '${file.name}'", true)
            } else {
                logActivity("Install failed: ${result.exceptionOrNull()?.message}", false)
            }
        } else {
            logActivity("APK file not found on device for '${file.name}'", false)
        }
        _installPromptFile.value = null
    }

    fun loadInstalledAppsList() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanningApps.value = true
            try {
                val list = AppInstallHelper.getInstalledAppsList(getApplication())
                _installedApps.value = list
                logActivity("Found ${list.size} apps to make APK from Android", true)
            } catch (e: Exception) {
                logActivity("Failed to load apps: ${e.message}", false)
            } finally {
                _isScanningApps.value = false
            }
        }
    }

    fun makeApk(app: InstalledApp, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            logActivity("Making APK file for ${app.appName}...", true)
            val result = AppInstallHelper.makeApkFromApp(getApplication(), app.packageName)
            if (result.isSuccess) {
                val file = result.getOrThrow()
                _sharedFiles.update { listOf(file) + it }
                _selectedCategory.value = FileCategory.APPS
                logActivity("Successfully made APK file: ${file.name}", true)
                onResult?.invoke(true, "APK made: ${file.name}")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to make APK"
                logActivity("APK make failed: $err", false)
                onResult?.invoke(false, err)
            }
        }
    }

    fun makeCustomApk(
        appName: String,
        packageName: String,
        version: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            logActivity("Creating custom APK package '$appName'...", true)
            val result = AppInstallHelper.createCustomApkPackage(
                getApplication(),
                appName,
                packageName,
                version
            )
            if (result.isSuccess) {
                val file = result.getOrThrow()
                _sharedFiles.update { listOf(file) + it }
                _selectedCategory.value = FileCategory.APPS
                logActivity("Created custom APK package: ${file.name}", true)
                onResult?.invoke(true, "Created APK: ${file.name}")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to create APK"
                logActivity("Custom APK creation failed: $err", false)
                onResult?.invoke(false, err)
            }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            logActivity("Scanning installed mobile apps...", true)
            val apps = AppInstallHelper.getInstalledMobileApps(getApplication())
            if (apps.isNotEmpty()) {
                _sharedFiles.update { current ->
                    val currentIds = current.map { it.id }.toSet()
                    val newApps = apps.filter { it.id !in currentIds }
                    newApps + current
                }
                _selectedCategory.value = FileCategory.APPS
                logActivity("Added ${apps.size} installed mobile app(s) to share", true)
            } else {
                _selectedCategory.value = FileCategory.APPS
                logActivity("Mobile app catalog ready", true)
            }
        }
    }

    private fun handleInstallRequest(fileId: String) {
        val file = _sharedFiles.value.find { it.id == fileId }
        if (file != null && file.isApk) {
            _installPromptFile.value = file
            logActivity("Web user requested install for '${file.name}' on mobile", true)
        }
    }

    fun sendMessageFromPhone(text: String) {
        if (text.isBlank()) return
        val msg = WebMessage(
            id = System.currentTimeMillis().toString(),
            sender = "Phone",
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isFromPhone = true
        )
        _messages.update { it + msg }
        logActivity("Message sent: ${text.trim()}", true)
    }

    fun setCategory(category: FileCategory) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun addSampleFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = FileHelper.createSampleFiles(getApplication())
            _sharedFiles.update { current ->
                val currentNames = current.map { it.name }.toSet()
                val newSamples = samples.filter { it.name !in currentNames }
                newSamples + current
            }
            logActivity("Sample files re-generated", true)
        }
    }

    private fun logActivity(message: String, isSuccess: Boolean) {
        val entry = ActivityLog(
            id = UUID.randomUUID().toString(),
            message = message,
            timestamp = System.currentTimeMillis(),
            isSuccess = isSuccess
        )
        _activityLogs.update { (listOf(entry) + it).take(50) }
    }

    override fun onCleared() {
        super.onCleared()
        server?.stop()
    }
}
