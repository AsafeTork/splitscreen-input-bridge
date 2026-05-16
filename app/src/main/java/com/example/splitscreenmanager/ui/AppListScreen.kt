package com.example.splitscreenmanager.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.core.graphics.drawable.toBitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import com.example.splitscreenmanager.model.AppInfo
import com.example.splitscreenmanager.service.ConsoleForegroundService
import com.example.splitscreenmanager.service.InputAccessibilityService
import com.example.splitscreenmanager.viewmodel.AppViewModel
import com.example.splitscreenmanager.R

// Constants
private const val DEFAULT_DPI = "600"
private const val PLAYER_1_INDEX = 0
private const val PLAYER_2_INDEX = 1
private const val MAX_SELECTED_APPS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppViewModel,
    context: Context,
    isShizukuAvailable: Boolean,
    hasSecureSettingsPermission: Boolean,
    isBatteryExempt: Boolean,
    systemLogs: List<AppViewModel.SystemLog>
) {
    val appList = viewModel.appList
    val selectedApps = viewModel.selectedApps
    val isLoading = viewModel.isLoading.value
    val currentDpi = viewModel.currentDpi.value
    val hasPermission = hasSecureSettingsPermission

    // Gamepad states
    val p1Id = viewModel.player1DeviceId.value
    val p2Id = viewModel.player2DeviceId.value
    val isBinding = viewModel.isBinding.value
    val showAdbTutorial = viewModel.showAdbTutorial.value

    // Observe gamepad info from manager
    val p1Name = viewModel.player1Name.value
    val p2Name = viewModel.player2Name.value

    var showDpiSettings by rememberSaveable { mutableStateOf(false) }
    var targetDpiText by rememberSaveable { mutableStateOf(DEFAULT_DPI) }

    // Sync AccessibilityService player IDs with ViewModel
    LaunchedEffect(p1Id, p2Id) {
        InputAccessibilityService.player1DeviceId = p1Id
        InputAccessibilityService.player2DeviceId = p2Id
    }

    // Use derived state for button enablement to avoid recomposition
    val isButtonEnabled by remember {
        derivedStateOf { viewModel.isButtonEnabled() }
    }

    // Use derived state for selected apps count
    val selectedAppsCount by remember {
        derivedStateOf { selectedApps.size }
    }

    // ADB Tutorial Dialog
    if (showAdbTutorial) {
        AdbTutorialDialog(
            context = context,
            onDismiss = { viewModel.toggleAdbTutorial() }
        )
    }

    // Lifecycle observer to refresh status on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.checkBatteryExemption(context)
                viewModel.updateShizukuStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            AppListTopBar(
                onInfoClick = { viewModel.toggleAdbTutorial() },
                onSettingsClick = { showDpiSettings = !showDpiSettings }
            )
        },
        bottomBar = {
            AppListBottomBar(
                hasPermission = hasPermission,
                isShizukuAvailable = isShizukuAvailable,
                currentDpi = currentDpi,
                targetDpiText = targetDpiText,
                isButtonEnabled = isButtonEnabled,
                selectedAppsCount = selectedAppsCount,
                context = context,
                viewModel = viewModel
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Shizuku Permission Banner
            if (!isShizukuAvailable) {
                item {
                    ShizukuPermissionBanner(
                        context = context,
                        viewModel = viewModel
                    )
                }
            }

            // Battery Optimization Banner
            if (!isBatteryExempt) {
                item {
                    BatteryOptimizationBanner(
                        onRequestExemption = { viewModel.requestBatteryExemption(context) }
                    )
                }
            }

            // Secure Settings Permission Banner
            if (!hasPermission && !isShizukuAvailable) {
                item { PermissionBanner(packageName = context.packageName) }
            }

            item {
                if (showDpiSettings && hasPermission) {
                    DpiSettingsCard(
                        targetDpi = targetDpiText,
                        onDpiChange = { targetDpiText = it }
                    )
                }
            }

            // Gamepad Slots Section
            item {
                GamepadSelectionSection(
                    p1Id = p1Id,
                    p1Name = p1Name,
                    p2Id = p2Id,
                    p2Name = p2Name,
                    isBinding = isBinding,
                    onStartBinding = { viewModel.startBinding(it) }
                )
            }

            // Play Store Policy Warning
            item {
                PlayStorePolicyWarning()
            }

            item {
                SectionHeader(text = "Selecionar Aplicativos")
            }

            if (isLoading) {
                item {
                    LoadingIndicator()
                }
            } else {
                items(appList, key = { it.packageName }) { app ->
                    val isSelected = remember(app, selectedApps) { selectedApps.contains(app) }
                    val orderIndex = remember(app, selectedApps) { selectedApps.indexOf(app) }

                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        AppListItem(
                            app = app,
                            isSelected = isSelected,
                            orderIndex = orderIndex,
                            onClick = { viewModel.toggleSelection(app) }
                        )
                    }
                }
            }

            // System Logs inside LazyColumn
            item {
                SystemLogsSection(viewModel)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListTopBar(
    onInfoClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = { Text("Split Screen Manager", fontWeight = FontWeight.Bold) },
        actions = {
            IconButton(onClick = onInfoClick) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "Tutorial ADB")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "DPI Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListBottomBar(
    hasPermission: Boolean,
    isShizukuAvailable: Boolean,
    currentDpi: Int,
    targetDpiText: String,
    isButtonEnabled: Boolean,
    selectedAppsCount: Int,
    context: Context,
    viewModel: AppViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasPermission) {
            Text(
                text = "DPI Atual: $currentDpi",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = {
                ConsoleForegroundService.startService(context)
                val dpi = targetDpiText.toIntOrNull() ?: DEFAULT_DPI.toInt()
                viewModel.launchConsoleMode(context, dpi)
            },
            enabled = isButtonEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (selectedAppsCount < MAX_SELECTED_APPS) "Selecione 2 apps" else "Iniciar Console",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (hasPermission || isShizukuAvailable) {
            TextButton(
                onClick = {
                    if (isShizukuAvailable) {
                        viewModel.cleanupConsoleSettings()
                    } else {
                        viewModel.restoreOriginalDPI(context)
                    }
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Finalizar Modo Console (Restaurar)")
            }
        }
    }
}

@Composable
fun ShizukuPermissionBanner(
    context: Context,
    viewModel: AppViewModel
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.padding(16.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Shizuku Desconectado", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Ative o Shizuku primeiro para usar os recursos do app.", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val granted = viewModel.requestShizukuPermission()
                    if (!granted) viewModel.openShizukuApp(context)
                }) {
                    Text("Conceder Permissão")
                }
                OutlinedButton(onClick = { viewModel.openShizukuApp(context) }) {
                    Text("Abrir Shizuku")
                }
            }
        }
    }
}

@Composable
fun BatteryOptimizationBanner(
    onRequestExemption: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Otimização de Bateria", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text("O Android pode matar o processo em background. Desative a otimização para este app.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onRequestExemption,
                modifier = Modifier.padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Ignorar Otimização", color = Color.White)
            }
        }
    }
}

@Composable
fun PlayStorePolicyWarning() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Aviso de Distribuição", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Este app utiliza APIs de sistema (Shizuku/ADB) não permitidas na Google Play Store. Use a versão oficial via APK.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun AdbTutorialDialog(
    context: Context,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tutorial ADB - Persistência") },
        text = {
            Column {
                Text("Para evitar que o sistema encerre o app durante jogos pesados, execute os comandos abaixo no seu PC:")
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "adb shell dumpsys deviceidle whitelist +${context.packageName}\nadb shell dumpsys deviceidle whitelist +moe.shizuku.privileged.api",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendi") }
        }
    )
}

@Composable
fun GamepadSelectionSection(
    p1Id: Int?,
    p1Name: String?,
    p2Id: Int?,
    p2Name: String?,
    isBinding: Int?,
    onStartBinding: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Configuração de Controles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Vincule cada controle a um jogador. Jogador 1 = metade superior, Jogador 2 = metade inferior.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GamepadSlot(
                label = "Jogador 1 (Superior)",
                deviceId = p1Id,
                deviceName = p1Name ?: "Nenhum",
                isBinding = isBinding == 1,
                onClick = { onStartBinding(1) },
                modifier = Modifier.weight(1f)
            )
            GamepadSlot(
                label = "Jogador 2 (Inferior)",
                deviceId = p2Id,
                deviceName = p2Name ?: "Nenhum",
                isBinding = isBinding == 2,
                onClick = { onStartBinding(2) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun GamepadSlot(
    label: String,
    deviceId: Int?,
    deviceName: String,
    isBinding: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isBinding) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else if (deviceId != null) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.height(100.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                if (isBinding) {
                    Text("Pressione um botão...", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.8f))
                } else if (deviceId != null) {
                    Text(deviceName, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("ID: $deviceId", fontSize = 10.sp)
                    Text("CONECTADO", fontSize = 10.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                } else {
                    Text("Toque para vincular", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PermissionBanner(packageName: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = "Permissão Necessária", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(text = "Para alterar o DPI, execute via ADB:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                Surface(
                    color = Color.Black.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                        fontSize = 10.sp,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun DpiSettingsCard(targetDpi: String, onDpiChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Simular DPI:", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = targetDpi,
                onValueChange = onDpiChange,
                modifier = Modifier.width(120.dp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface)
            )
        }
    }
}

@Composable
fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    orderIndex: Int = -1,
    onClick: () -> Unit
) {
    // Use remember to cache computed values and avoid recomposition
    // Access colorScheme outside remember because it's a Composable getter
    val colorScheme = MaterialTheme.colorScheme

    val backgroundColor = remember(orderIndex, colorScheme) {
        when (orderIndex) {
            PLAYER_1_INDEX -> colorScheme.primaryContainer.copy(alpha = 0.9f)
            PLAYER_2_INDEX -> colorScheme.secondaryContainer.copy(alpha = 0.9f)
            else -> colorScheme.surfaceVariant.copy(alpha = 0.3f)
        }
    }

    val borderColor = remember(orderIndex, colorScheme) {
        when (orderIndex) {
            PLAYER_1_INDEX -> colorScheme.primary
            PLAYER_2_INDEX -> colorScheme.secondary
            else -> Color.Transparent
        }
    }

    val playerLabel = remember(orderIndex) {
        when (orderIndex) {
            PLAYER_1_INDEX -> "P1"
            PLAYER_2_INDEX -> "P2"
            else -> null
        }
    }

    val positionLabel = remember(orderIndex) {
        when (orderIndex) {
            PLAYER_1_INDEX -> "TOPO"
            PLAYER_2_INDEX -> "BASE"
            else -> null
        }
    }

    val positionLabelColor = remember(orderIndex, colorScheme) {
        when (orderIndex) {
            PLAYER_1_INDEX -> colorScheme.primary
            PLAYER_2_INDEX -> colorScheme.secondary
            else -> colorScheme.onSurface
        }
    }

    val packageNameColor = remember(orderIndex, colorScheme) {
        if (orderIndex >= 0) {
            colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        } else {
            colorScheme.onSurfaceVariant
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Player label or app icon
            if (orderIndex >= 0) {
                PlayerLabel(label = playerLabel!!)
                Spacer(modifier = Modifier.width(16.dp))
            } else {
                AppIcon(icon = app.icon)
                Spacer(modifier = Modifier.width(16.dp))
            }

            AppInfoColumn(
                appName = app.name,
                packageName = app.packageName,
                positionLabel = positionLabel,
                positionLabelColor = positionLabelColor,
                packageNameColor = packageNameColor
            )

            // Arrow indicator
            if (orderIndex == PLAYER_1_INDEX) {
                PositionArrow(
                    icon = Icons.Filled.ArrowUpward,
                    contentDescription = "Topo",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (orderIndex == PLAYER_2_INDEX) {
                PositionArrow(
                    icon = Icons.Filled.ArrowDownward,
                    contentDescription = "Base",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun PlayerLabel(label: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun AppIcon(icon: android.graphics.drawable.Drawable) {
    Image(
        bitmap = icon.toBitmap().asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun RowScope.AppInfoColumn(
    appName: String,
    packageName: String,
    positionLabel: String?,
    positionLabelColor: Color,
    packageNameColor: Color
) {
    Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = appName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (positionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                PositionLabel(
                    text = positionLabel,
                    color = positionLabelColor
                )
            }
        }
        Text(
            text = packageName,
            fontSize = 11.sp,
            color = packageNameColor
        )
    }
}

@Composable
private fun PositionLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(
                Color(0x20000000),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun PositionArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(24.dp)
    )
}

// Preview for testing components
@Preview(showBackground = true)
@Composable
fun AppListItemPreview() {
    val context = LocalContext.current
    MaterialTheme {
        val mockApp = AppInfo(
            name = "Test App",
            packageName = "com.test.app",
            icon = context.getDrawable(android.R.drawable.sym_def_app_icon)!!
        )

        AppListItem(
            app = mockApp,
            isSelected = true,
            orderIndex = 0,
            onClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppListItemUnselectedPreview() {
    val context = LocalContext.current
    MaterialTheme {
        val mockApp = AppInfo(
            name = "Test App",
            packageName = "com.test.app",
            icon = context.getDrawable(android.R.drawable.sym_def_app_icon)!!
        )

        AppListItem(
            app = mockApp,
            isSelected = false,
            orderIndex = -1,
            onClick = {}
        )
    }
}