package com.example.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.FileCategory
import com.example.model.SharedFile
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebShareScreen(
    viewModel: WebShareViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()
    val sharedFiles by viewModel.sharedFiles.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val activityLogs by viewModel.activityLogs.collectAsStateWithLifecycle()

    // Dialog state controllers
    var fileToRename by remember { mutableStateOf<SharedFile?>(null) }
    var showRenameDeviceDialog by remember { mutableStateOf(false) }
    var showFullscreenQR by remember { mutableStateOf(false) }
    var showActivityLogs by remember { mutableStateOf(false) }
    var showAddOptionsDialog by remember { mutableStateOf(false) }

    // System file picker launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedFiles(uris)
            Toast.makeText(context, "Added ${uris.size} file(s) to share", Toast.LENGTH_SHORT).show()
        }
    }

    // Photo picker launcher (Android Photo Picker zero-permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedFiles(uris)
            Toast.makeText(context, "Added ${uris.size} photo(s) to share", Toast.LENGTH_SHORT).show()
        }
    }

    // Filter files
    val filteredFiles = remember(sharedFiles, selectedCategory, searchQuery) {
        sharedFiles.filter { file ->
            val matchCategory = selectedCategory == FileCategory.ALL || file.category == selectedCategory
            val matchQuery = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }
    }

    val totalStorageSize = remember(sharedFiles) {
        val total = sharedFiles.sumOf { it.size }
        if (total <= 0) "0 B"
        else {
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(total.toDouble()) / Math.log10(1024.0)).toInt()
            val formatted = String.format("%.1f", total / Math.pow(1024.0, digitGroups.toDouble()))
            "$formatted ${units.getOrElse(digitGroups) { "B" }}"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MidnightNavy,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElectricCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = MidnightNavy,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Web Share",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextLight
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ElectricCyan.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "My File",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Wireless Local Web Sharing & Renaming",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    // Activity log button
                    IconButton(
                        onClick = { showActivityLogs = true },
                        modifier = Modifier.testTag("activity_log_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Activity Log",
                            tint = CyanLight
                        )
                    }

                    // Re-add sample files button
                    IconButton(
                        onClick = {
                            viewModel.addSampleFiles()
                            Toast.makeText(context, "Sample files refreshed", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("refresh_samples_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Sample files",
                            tint = AmberWarning
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MidnightNavy
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddOptionsDialog = true },
                containerColor = ElectricCyan,
                contentColor = MidnightNavy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_files_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Files")
                    Text("Add Files", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 90.dp, top = 8.dp)
        ) {
            // 1. Server Status & Web URL card
            item {
                ServerStatusCard(
                    status = serverStatus,
                    onToggleServer = { viewModel.toggleServer() },
                    onOpenQRCode = { showFullscreenQR = true },
                    onOpenSettings = { showRenameDeviceDialog = true }
                )
            }

            // 2. Station Name Bar (with rename button)
            item {
                StationInfoBar(
                    deviceName = serverStatus.deviceName,
                    onEditDeviceName = { showRenameDeviceDialog = true }
                )
            }

            // 3. Stats Overview Row
            item {
                StatsRow(
                    totalFiles = sharedFiles.size,
                    totalSize = totalStorageSize,
                    receivedCount = sharedFiles.count { it.isReceivedFromWeb },
                    messageCount = messages.size
                )
            }

            // 4. Category Filter Chips
            item {
                CategoryFilterBar(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { viewModel.setCategory(it) }
                )
            }

            // 5. Search Field
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search files by name...", color = TextMuted) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SlateSurface,
                        unfocusedContainerColor = SlateSurface,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("search_files_input")
                )
            }

            // 6. Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedCategory.label} (${filteredFiles.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextLight
                    )
                    Text(
                        text = "Tip: Tap pencil to rename",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }

            // 7. Files List
            if (filteredFiles.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No files found in this section",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextLight,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Tap '+ Add Files' below or click 'Sample files' above to add documents and photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredFiles, key = { it.id }) { file ->
                    SharedFileCard(
                        file = file,
                        onRename = { fileToRename = file },
                        onRemove = { viewModel.removeFile(file.id) }
                    )
                }
            }

            // 8. Live Notes / Messaging between phone and web browser
            item {
                Spacer(modifier = Modifier.height(8.dp))
                MessagingSheet(
                    messages = messages,
                    onSendMessage = { viewModel.sendMessageFromPhone(it) }
                )
            }
        }
    }

    // Modal: Rename File Dialog (direct user request: "name change by web share my file")
    fileToRename?.let { target ->
        RenameModalDialog(
            currentName = target.name,
            onDismiss = { fileToRename = null },
            onConfirm = { newName ->
                viewModel.renameFile(target.id, newName)
                fileToRename = null
                Toast.makeText(context, "Renamed to $newName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Modal: Change Device / Station Name
    if (showRenameDeviceDialog) {
        RenameDeviceDialog(
            currentDeviceName = serverStatus.deviceName,
            onDismiss = { showRenameDeviceDialog = false },
            onConfirm = { newName ->
                viewModel.updateDeviceName(newName)
                showRenameDeviceDialog = false
            }
        )
    }

    // Modal: Fullscreen QR Code for easy scanning
    if (showFullscreenQR) {
        FullscreenQRCodeDialog(
            url = serverStatus.serverUrl,
            deviceName = serverStatus.deviceName,
            onDismiss = { showFullscreenQR = false }
        )
    }

    // Modal: Activity Logs
    if (showActivityLogs) {
        ActivityLogSheet(
            logs = activityLogs,
            onDismiss = { showActivityLogs = false }
        )
    }

    // Modal: Add Files Options
    if (showAddOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showAddOptionsDialog = false },
            containerColor = DeepCobalt,
            title = {
                Text("Select Files to Share", color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose how you want to add files to your Web Share station:",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Option 1: Documents & Any Files
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddOptionsDialog = false
                                try {
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "File picker not available", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = ElectricCyan)
                            Column {
                                Text("Browse Documents & Files", fontWeight = FontWeight.SemiBold, color = TextLight)
                                Text("PDFs, DOCs, TXT, APKs, Archives", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }

                    // Option 2: Photos & Videos
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showAddOptionsDialog = false
                                try {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                } catch (e: Exception) {
                                    documentPickerLauncher.launch(arrayOf("image/*", "video/*"))
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = BrightEmerald)
                            Column {
                                Text("Photos & Videos", fontWeight = FontWeight.SemiBold, color = TextLight)
                                Text("Select images or videos from gallery", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddOptionsDialog = false }) {
                    Text("Close", color = ElectricCyan)
                }
            }
        )
    }
}
