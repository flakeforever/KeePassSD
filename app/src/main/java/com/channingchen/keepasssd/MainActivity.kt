package com.channingchen.keepasssd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.channingchen.keepasssd.ui.theme.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePassSDTheme {
                val viewModel: MainViewModel = viewModel()
                val unlockState by viewModel.unlockState.collectAsState()
                val showSettings by viewModel.showSettings.collectAsState()
                
                val context = LocalContext.current
                
                var currentScreen by remember { mutableStateOf("Unlock") }

                LaunchedEffect(unlockState) {
                    val state = unlockState
                    if (state is UnlockState.Success) {
                        Toast.makeText(context, "Unlocked: ${state.databaseName}", Toast.LENGTH_SHORT).show()
                        currentScreen = "Main"
                    } else if (state is UnlockState.Idle) {
                        currentScreen = "Unlock"
                    }
                }
                
                // Security: Auto-lock when screen turns off
                DisposableEffect(context) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                                viewModel.resetState()
                            }
                        }
                    }
                    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
                    context.registerReceiver(receiver, filter)
                    
                    onDispose {
                        context.unregisterReceiver(receiver)
                    }
                }

                if (showSettings) {
                    SettingsScreen(viewModel = viewModel)
                } else if (currentScreen == "Main") {
                    MainScreen(
                        viewModel = viewModel,
                        onNavigateToUnlock = { 
                            viewModel.resetState()
                            currentScreen = "Unlock" 
                        }
                    )
                } else {
                    UnlockScreen(
                        viewModel = viewModel,
                        unlockState = unlockState
                    )
                }
            }
        }
    }
}

fun extractFileName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "database.kdbx"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) {
                    cursor.getString(idx)?.let { name = it }
                }
            }
        }
    } catch (e: Exception) {}
    return name
}

@Composable
fun KeePassIcon(standardIconId: Int, customIconData: ByteArray?, tint: Color, modifier: Modifier) {
    var customBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
    
    if (customIconData != null && customIconData.isNotEmpty()) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(customIconData, 0, customIconData.size)
            if (bitmap != null) {
                customBitmap = bitmap.asImageBitmap()
            }
        } catch (e: Exception) {}
    }
    
    if (customBitmap != null) {
        Image(
            bitmap = customBitmap,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        val iconVec = when(standardIconId) {
            0 -> Icons.Default.VpnKey     // Key (Password)
            1 -> Icons.Default.Public     // World (Browser)
            2 -> Icons.Default.Warning    // Warning
            3 -> Icons.Default.Dns        // Network Server
            19 -> Icons.Default.Email     // Email
            48 -> Icons.Default.Folder    // Folder
            61 -> Icons.Default.Person    // Person
            else -> Icons.Default.Lock    // Fallback
        }
        Icon(iconVec, null, tint = tint, modifier = modifier)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onNavigateToUnlock: () -> Unit) {
    val colors = LocalNeumorphicColors.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<VaultItem?>(null) }
    
    var showBottomSheet by remember { mutableStateOf(false) }
    val groupStack = remember { mutableStateListOf<VaultGroup>() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val rootGroup by viewModel.rootGroup.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.connectBle(context)
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                viewModel.connectBle(context)
            }
        } else {
            viewModel.connectBle(context)
        }
    }


    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        searchQuery = ""
                    })
                }
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                NeumorphicIconButton(
                    icon = Icons.Default.Lock,
                    onClick = onNavigateToUnlock,
                    iconTint = colors.textPrimary 
                )
                
                // Restored Settings Button
                NeumorphicIconButton(
                    icon = Icons.Default.Settings,
                    onClick = { viewModel.toggleSettings(true) }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Title
            EngravedText(
                text = "KeePassSD",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Search Box and Selected List Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    NeumorphicCard(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        cornerRadius = 28.dp,
                        innerPadding = 0.dp
                    ) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search title/user...", color = colors.textSecondary) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = colors.accent,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            ),
                            modifier = Modifier.fillMaxSize(),
                            singleLine = true
                        )
                    }

                    // Global Independent Search Overlay
                    if (searchQuery.isNotEmpty()) {
                        val allItems = mutableListOf<VaultItem>()
                        fun collectItems(g: VaultGroup?) {
                            g?.let {
                                allItems.addAll(it.items)
                                it.subGroups.forEach { sub -> collectItems(sub) }
                            }
                        }
                        collectItems(rootGroup)

                        val searchResults = allItems.filter {
                            it.title.contains(searchQuery, true) || it.username.contains(searchQuery, true)
                        }.take(15)

                        val yOffset = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.roundToPx() }

                        androidx.compose.ui.window.Popup(
                            alignment = Alignment.TopStart,
                            offset = androidx.compose.ui.unit.IntOffset(0, yOffset),
                            properties = androidx.compose.ui.window.PopupProperties(focusable = false) // Preserves keyboard focus!
                        ) {
                            Box {
                                NeumorphicCard(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .heightIn(max = 280.dp),
                                    cornerRadius = 16.dp,
                                    innerPadding = 8.dp
                                ) {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        if (searchResults.isEmpty()) {
                                            item {
                                                Text("No matching entries found", color = colors.textSecondary, modifier = Modifier.padding(16.dp))
                                            }
                                        } else {
                                            items(searchResults) { item ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            selectedItem = item
                                                            searchQuery = ""
                                                            focusManager.clearFocus()
                                                        }
                                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    KeePassIcon(
                                                        standardIconId = item.standardIconId,
                                                        customIconData = item.customIconData,
                                                        tint = colors.textSecondary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(Modifier.width(16.dp))
                                                    Column {
                                                        Text(if (item.title.isEmpty()) "Untitled" else item.title, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                                        if (item.username.isNotEmpty()) {
                                                            Text(item.username, color = colors.textSecondary, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                                Divider(color = colors.darkShadow.copy(alpha=0.15f), modifier = Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Box {
                    NeumorphicIconButton(
                        icon = Icons.Default.List,
                        onClick = { 
                            searchQuery = ""
                            focusManager.clearFocus()
                            showBottomSheet = true 
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Main Content Area (Selected Item)
            NeumorphicCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp),
                cornerRadius = 24.dp,
                hasPremiumBorder = true,
                borderAlpha = 0.85f // High contrast for the premium feel
            ) {
                if (selectedItem != null) {
                    val item = selectedItem!!
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeePassIcon(
                                standardIconId = item.standardIconId,
                                customIconData = item.customIconData,
                                tint = colors.accent,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = if (item.title.isEmpty()) "Untitled" else item.title, 
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (item.url.isEmpty()) item.groupName else item.url, 
                                    fontSize = 14.sp, color = colors.accent,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        LCDDisplay(text = if(item.username.isEmpty()) "(No Username)" else item.username)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = colors.textSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Search or select an entry",
                            color = colors.textSecondary,
                            fontSize = 18.sp
                        )
                    }
                }
            }
            
            var showInfoDialog by remember { mutableStateOf(false) }
            val deviceInfo by viewModel.deviceInfo.collectAsState()

            if (showInfoDialog) {
                DeviceInfoDialog(
                    info = deviceInfo,
                    onDismiss = { showInfoDialog = false }
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            val isBleConnected by viewModel.isBleConnected.collectAsState()
            val isSending by viewModel.isBleSending.collectAsState()
            
            // Animation for Pulsing Dot
            val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Scale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "Alpha"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clickable(
                        enabled = !isSending,
                        onClick = {
                            if (isBleConnected) {
                                viewModel.fetchDeviceInfo()
                                showInfoDialog = true
                            } else {
                                if (android.os.Build.VERSION.SDK_INT >= 31) {
                                    if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        viewModel.connectBle(context)
                                    }
                                } else {
                                    viewModel.connectBle(context)
                                }
                            }
                        }
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isBleConnected && !isSending) {
                        // Static Glow
                        Box(
                            modifier = Modifier
                                .size(12.dp * pulseScale)
                                .background(Color(0xFF4CAF50).copy(alpha = 0.2f), shape = CircleShape)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(if (!isBleConnected) pulseScale else 1f)
                            .alpha(if (!isBleConnected) pulseAlpha else 1f)
                            .background(
                                color = if (isBleConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                shape = CircleShape
                            )
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isBleConnected) "BRIDGE CONNECTED" else "BRIDGE DISCONNECTED",
                    color = if (isBleConnected) colors.textPrimary else colors.textSecondary.copy(alpha = pulseAlpha),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            // Bottom Instruction Text
            Text(
                text = "Press below to send via HID",
                color = if (isBleConnected) colors.textSecondary else colors.textSecondary.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action Buttons
            ActionButtons(viewModel = viewModel, selectedItem = selectedItem)
            
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom Sheet Drawer for Hierarchical Selection (Filters removed, Independent)
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { 
                    showBottomSheet = false
                    groupStack.clear()
                },
                sheetState = sheetState,
                containerColor = colors.background, // Match Neumorphism base color
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                    // Drawer Top Navigation Bar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (groupStack.isNotEmpty()) {
                            IconButton(onClick = { groupStack.removeAt(groupStack.size - 1) }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = groupStack.last().name,
                                color = colors.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = "Groups",
                                color = colors.textPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Divider(color = colors.darkShadow.copy(alpha = 0.3f))

                    // Drawer Scrollable Content
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        val currentSubGroups = if (groupStack.isEmpty()) rootGroup?.subGroups ?: emptyList() else groupStack.last().subGroups
                        val currentItems = if (groupStack.isEmpty()) rootGroup?.items ?: emptyList() else groupStack.last().items

                        // 1. SHOW SUBGROUPS
                        if (currentSubGroups.isNotEmpty()) {
                            items(currentSubGroups) { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { groupStack.add(group) }
                                        .padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        KeePassIcon(
                                            standardIconId = group.standardIconId,
                                            customIconData = group.customIconData,
                                            tint = colors.textPrimary,
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        Text(
                                            text = group.name,
                                            color = colors.textPrimary,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary.copy(alpha=0.6f), modifier = Modifier.size(20.dp))
                                }
                                Divider(color = colors.darkShadow.copy(alpha=0.1f), modifier = Modifier.padding(horizontal = 24.dp))
                            }
                        }

                        // 2. SHOW ITEMS
                        if (currentItems.isNotEmpty()) {
                            // Add a small spacer or header if there were subgroups
                            if (currentSubGroups.isNotEmpty()) {
                                item { Spacer(Modifier.height(8.dp)) }
                            }

                            items(currentItems) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            selectedItem = item
                                            showBottomSheet = false // closes drawer
                                            focusManager.clearFocus()
                                        }
                                        .padding(horizontal = 24.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    KeePassIcon(
                                        standardIconId = item.standardIconId,
                                        customIconData = item.customIconData,
                                        tint = colors.accent.copy(alpha=0.8f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = if (item.title.isEmpty()) "Untitled" else item.title,
                                            color = colors.textPrimary,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (item.username.isNotEmpty()) {
                                            Text(
                                                text = item.username,
                                                color = colors.textSecondary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                                Divider(color = colors.darkShadow.copy(alpha=0.08f), modifier = Modifier.padding(horizontal = 24.dp))
                            }
                        }

                        // Empty State if none
                        if (currentSubGroups.isEmpty() && currentItems.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("This group is empty", color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LCDDisplay(text: String, modifier: Modifier = Modifier) {
    // --- LCD Color Palette for Text Rendering ---
    val lcdInkColor     = Color(0xFF42523E) // Lower contrast olive-green ink
    val lcdInkShadow    = Color(0xFF1A2418).copy(alpha = 0.20f) // Softened ink drop-shadow

    // Outer border colors for the recessed inset frame effect
    val borderDark    = Color(0xFF8A9485).copy(alpha = 0.85f) // top-left inner dark rim

    Box(modifier = modifier.fillMaxWidth()) {
        // ── OUTER FRAME: Neumorphic inset border (the recessed bezel) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .drawBehind {
                    val cornerPx = 0f
                    val strokeW  = 2.5.dp.toPx()

                    // Top-left dark bevels (recessed feel)
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokeW
                            setShadowLayer(6f, 2f, 2f, borderDark.copy(alpha = 0.6f).toArgb())
                            color = borderDark.toArgb()
                        }
                        canvas.nativeCanvas.drawRoundRect(
                            strokeW / 2, strokeW / 2,
                            size.width - strokeW / 2, size.height - strokeW / 2,
                            cornerPx, cornerPx,
                            paint
                        )
                    }
                }
        ) {
            // ── LCD PANEL SURFACE: Image Background ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp) // inner inset from bezel edge
            ) {
                Image(
                    painter = painterResource(id = R.drawable.lcd_bg),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop // Mantains ratio and fills the space
                )

                // ── Text rendering: Clean text with shadow ──
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box {
                        // Layer 1: Ink drop-shadow
                        Text(
                            text = text,
                            color = lcdInkShadow,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = 1.0.sp,
                            modifier = Modifier.offset(x = 0.8.dp, y = 1.2.dp)
                        )
                        // Layer 2: Main ink body
                        Text(
                            text = text,
                            color = lcdInkColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = 1.0.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReadOnlyField(value: String) {
    val colors = LocalNeumorphicColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NeumorphicCard(
            modifier = Modifier.weight(1f).height(48.dp),
            cornerRadius = 12.dp,
            innerPadding = 0.dp,
            isPressed = true, // Engraved / recessed feel
            backgroundColor = colors.textPrimary // Solid dark gray base
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = value, 
                    color = colors.background, // Brilliant contrast against dark gray
                    modifier = Modifier.weight(1f), 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ActionButtons(viewModel: MainViewModel, selectedItem: VaultItem?) {
    val colors = LocalNeumorphicColors.current
    val isSending by viewModel.isBleSending.collectAsState()
    val isConnected by viewModel.isBleConnected.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    
    val baseEnabled = selectedItem != null && !isSending && isConnected
    val userEnabled = baseEnabled && selectedItem!!.username.isNotEmpty()
    val passEnabled = baseEnabled && (selectedItem!!.entry.fields["Password"]?.content?.isNotEmpty() == true)
    val tabEnterEnabled = baseEnabled
    val undoEnabled = !isSending && isConnected && canUndo

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { selectedItem?.let { viewModel.sendUsername(it) } },
                enabled = userEnabled,
                modifier = Modifier.weight(1.5f).height(48.dp),
                innerPadding = 0.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.textPrimary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Username", color = if (isSending) colors.accent else colors.textPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            NeumorphicButton(
                onClick = { selectedItem?.let { viewModel.sendPassword(it) } },
                enabled = passEnabled,
                modifier = Modifier.weight(1.5f).height(48.dp),
                innerPadding = 0.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                    } else {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.textPrimary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Password", color = if (isSending) colors.accent else colors.textPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { viewModel.sendTab() },
                enabled = tabEnterEnabled,
                modifier = Modifier.weight(1f).height(64.dp),
                innerPadding = 0.dp
            ) {
                Text(
                    text = "TAB",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            NeumorphicButton(
                onClick = { viewModel.sendEnter() },
                enabled = tabEnterEnabled,
                modifier = Modifier.weight(1f).height(64.dp),
                innerPadding = 0.dp
            ) {
                Text(
                    text = "ENTER",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            NeumorphicButton(
                onClick = { viewModel.sendUndo() },
                enabled = undoEnabled, 
                modifier = Modifier.weight(1f).height(64.dp),
                innerPadding = 0.dp
            ) {
                Text(
                    text = "UNDO",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/* Original ActionButtons removed */
private fun ActionButtons_Original_Skip() {}


@Composable
fun UnlockScreen(viewModel: MainViewModel, unlockState: UnlockState) {
    val colors = LocalNeumorphicColors.current
    val context = LocalContext.current

    var databaseFile by rememberSaveable { mutableStateOf("") }
    var databaseUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    var password by rememberSaveable { mutableStateOf("") }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var keyfile by rememberSaveable { mutableStateOf("") }
    var keyfileUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var useKeyfile by rememberSaveable { mutableStateOf(false) }
    var keyfileVisible by rememberSaveable { mutableStateOf(false) }

    var hwKey by rememberSaveable { mutableStateOf("None") }
    var useHwKey by rememberSaveable { mutableStateOf(false) }

    val rememberLastFile by viewModel.rememberLastFile.collectAsState()
    val savedUriStr by viewModel.databaseUriStr.collectAsState()
    val savedKeyfileUriStr by viewModel.keyfileUriStr.collectAsState()
    val prefUseKeyfile by viewModel.useKeyfilePref.collectAsState()
    val prefUseHwKey by viewModel.useHwKeyPref.collectAsState()
    val prefHwKeyOption by viewModel.hwKeyOption.collectAsState()

    LaunchedEffect(Unit) {
        // Only load if current local state is empty (prevents overwriting rememberSaveable on recreation)
        if (databaseUri == null && rememberLastFile) {
            if (savedUriStr.isNotEmpty()) {
                try {
                    val savedUri = Uri.parse(savedUriStr)
                    databaseUri = savedUri
                    databaseFile = extractFileName(context, savedUri)
                } catch (e: Exception) {}
            }
            if (savedKeyfileUriStr.isNotEmpty()) {
                try {
                    val savedUri = Uri.parse(savedKeyfileUriStr)
                    keyfileUri = savedUri
                    keyfile = extractFileName(context, savedUri)
                } catch (e: Exception) {}
            }
            useKeyfile = prefUseKeyfile
            useHwKey = prefUseHwKey
            hwKey = prefHwKeyOption
        }
    }

    val dbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            if (name.endsWith(".kdbx", ignoreCase = true)) {
                databaseFile = name
                databaseUri = uri
                try {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {}
            } else {
                Toast.makeText(context, "Please select a .kdbx file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            keyfile = name
            keyfileUri = uri
            useKeyfile = name.isNotEmpty()
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
        }
    }


    // Collect hardware key results relayed by KeyDriverProxyActivity via ViewModel SharedFlow
    LaunchedEffect(Unit) {
        viewModel.hwKeyResult.collect { result ->
            result.fold(
                onSuccess = { responseBytes ->
                    if (responseBytes != null && databaseUri != null) {
                        viewModel.unlockDatabase(
                            context, databaseUri!!, password,
                            if (useKeyfile) keyfileUri else null,
                            responseBytes, useKeyfile, hwKey, useHwKey
                        )
                    } else {
                        viewModel.setUnlockError("Hardware Key cancelled or returned no response.")
                    }
                },
                onFailure = { e ->
                    viewModel.setUnlockError(e.message ?: "Hardware Key error.")
                }
            )
        }
    }

    // Display Error Toasts
    LaunchedEffect(unlockState) {
        if (unlockState is UnlockState.Error) {
            Toast.makeText(context, unlockState.message, Toast.LENGTH_LONG).show()
            viewModel.resetState()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background
    ) { innerPadding ->
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 24.dp)
                        .verticalScroll(scrollState)
                        // Removed imePadding() to prevent double-padding collapse with adjustResize
                ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header (Database Open button + Settings)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeumorphicIconButton(
                        icon = Icons.Default.Add, // Using Add as File Open
                        onClick = { dbLauncher.launch(arrayOf("*/*")) },
                        iconTint = colors.textPrimary
                    )
                    if (databaseFile.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = databaseFile,
                            color = colors.textSecondary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
                
                // Keep Settings Button
                NeumorphicIconButton(
                    icon = Icons.Default.Settings,
                    onClick = { viewModel.toggleSettings(true) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            EngravedText(
                text = "KeePassSD",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Password Row
            InputRow(
                label = "Password",
                value = password,
                onValueChange = { 
                    password = it
                    usePassword = it.isNotEmpty()
                },
                isObscured = !passwordVisible,
                onToggleVisibility = { passwordVisible = !passwordVisible },
                showVisibilityToggle = true,
                switchChecked = usePassword,
                onSwitchChange = { usePassword = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (databaseUri != null && unlockState !is UnlockState.Loading) {
                            if (useHwKey && hwKey == "YubiKey Challenge-Response") {
                                viewModel.requestYubiKeyChallenge(context, databaseUri!!) { challengeBytes ->
                                    if (challengeBytes != null) {
                                        val driverIntent = KeyDriverHelper.buildChallengeIntent(challengeBytes)
                                        if (driverIntent != null) {
                                            val proxyIntent = Intent(context, KeyDriverProxyActivity::class.java).apply {
                                                putExtra(KeyDriverProxyActivity.EXTRA_DRIVER_INTENT, driverIntent)
                                            }
                                            context.startActivity(proxyIntent)
                                        } else {
                                            viewModel.setUnlockError("No Hardware Key Driver installed.")
                                        }
                                    }
                                }
                            } else {
                                viewModel.unlockDatabase(context, databaseUri!!, password, if (useKeyfile) keyfileUri else null, null, useKeyfile, hwKey, useHwKey)
                            }
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Keyfile Row
            FileSelectRow(
                label = "Keyfile",
                selectedFile = keyfile,
                onFileClick = {
                    fileLauncher.launch(arrayOf("*/*"))
                },
                isObscured = !keyfileVisible,
                onToggleVisibility = { keyfileVisible = !keyfileVisible },
                switchChecked = useKeyfile,
                onSwitchChange = { useKeyfile = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Hardware Key Row
            val hwOptions = listOf("None", "YubiKey Challenge-Response")
            DropdownRow(
                label = "Hardware key",
                options = hwOptions,
                selectedValue = hwKey,
                onValueSelected = { 
                    hwKey = it
                    useHwKey = it != "None"
                },
                switchChecked = useHwKey,
                onSwitchChange = { useHwKey = it }
            )

            // Fixed spacer instead of weight(1f) to work with verticalScroll
            Spacer(modifier = Modifier.height(32.dp))

            // Unlock Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (unlockState is UnlockState.Loading) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
                
                val dbSelected = databaseUri != null
                NeumorphicIconButton(
                    icon = Icons.Default.LockOpen,
                    onClick = { 
                        if (dbSelected && unlockState !is UnlockState.Loading) {
                            if (useHwKey && hwKey == "YubiKey Challenge-Response") {
                                viewModel.requestYubiKeyChallenge(context, databaseUri!!) { challengeBytes ->
                                    if (challengeBytes != null) {
                                        // Build the Key Driver intent
                                        val driverIntent = KeyDriverHelper.buildChallengeIntent(challengeBytes)
                                        if (driverIntent != null) {
                                            val proxyIntent = Intent(context, KeyDriverProxyActivity::class.java).apply {
                                                putExtra(KeyDriverProxyActivity.EXTRA_DRIVER_INTENT, driverIntent)
                                            }
                                            context.startActivity(proxyIntent)
                                        } else {
                                            viewModel.setUnlockError("No Hardware Key Driver installed. Please install KeePassDX Key Driver or ykDroid.")
                                        }
                                    }
                                }
                            } else {
                                viewModel.unlockDatabase(context, databaseUri!!, password, if (useKeyfile) keyfileUri else null, null, useKeyfile, hwKey, useHwKey)
                            }
                        } 
                    },
                    modifier = Modifier.padding(bottom = 8.dp).size(64.dp),
                    iconTint = if (dbSelected) colors.textPrimary else colors.textSecondary.copy(alpha = 0.3f),
                    cornerRadius = 16.dp
                )
            }
        }
    }
}

@Composable
fun InputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isObscured: Boolean,
    onToggleVisibility: () -> Unit,
    showVisibilityToggle: Boolean,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = LocalNeumorphicColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NeumorphicCard(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            cornerRadius = 14.dp,
            innerPadding = 0.dp
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(label, color = colors.textSecondary, fontSize = 14.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent {
                        if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                            if (it.type == KeyEventType.KeyUp) {
                                // Trigger the IME action manually if onKeyEvent handles it
                                keyboardActions.onDone?.let { action ->
                                    action(object : KeyboardActionScope {
                                        override fun defaultKeyboardAction(imeAction: ImeAction) {}
                                    })
                                }
                            }
                            true // Consume to prevent newline
                        } else {
                            false
                        }
                    },
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                singleLine = true,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = if (isObscured) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    if (showVisibilityToggle) {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(
                                imageVector = if (isObscured) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isObscured) "Show" else "Hide",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        NeumorphicSwitch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange
        )
    }
}

@Composable
fun DropdownRow(
    label: String,
    options: List<String>,
    selectedValue: String,
    onValueSelected: (String) -> Unit,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit
) {
    val colors = LocalNeumorphicColors.current
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).height(52.dp)) {
            NeumorphicCard(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { expanded = true },
                cornerRadius = 14.dp,
                innerPadding = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (selectedValue.isEmpty()) label else selectedValue,
                        color = if (selectedValue.isEmpty() || selectedValue == "None") colors.textSecondary else colors.textPrimary,
                        fontSize = 14.sp
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colors.background)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = colors.textPrimary, fontSize = 14.sp) },
                        onClick = {
                            onValueSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        NeumorphicSwitch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange
        )
    }
}

@Composable
fun FileSelectRow(
    label: String,
    selectedFile: String,
    onFileClick: () -> Unit,
    isObscured: Boolean,
    onToggleVisibility: () -> Unit,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit
) {
    val colors = LocalNeumorphicColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NeumorphicCard(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            cornerRadius = 14.dp,
            innerPadding = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TextField(
                    value = selectedFile,
                    onValueChange = { },
                    readOnly = true,
                    placeholder = { Text(label, color = colors.textSecondary, fontSize = 14.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = colors.accent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxSize(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    visualTransformation = if (isObscured && selectedFile.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = {
                        if (selectedFile.isNotEmpty()) {
                            IconButton(onClick = onToggleVisibility) {
                                Icon(
                                    imageVector = if (isObscured) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isObscured) "Show" else "Hide",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
                
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(end = 56.dp) 
                        .clickable(onClick = onFileClick)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        NeumorphicSwitch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange
        )
    }
}
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val colors = LocalNeumorphicColors.current
    val diagnosticResult by viewModel.diagnosticResult.collectAsState()
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeumorphicIconButton(
                    icon = Icons.Default.ArrowBack,
                    onClick = { viewModel.toggleSettings(false) }
                )
                Spacer(modifier = Modifier.width(16.dp))
                EngravedText(
                    text = "Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            val rememberLastFile by viewModel.rememberLastFile.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Remember last opened file", color = colors.textPrimary, fontSize = 14.sp)
                NeumorphicSwitch(
                    checked = rememberLastFile,
                    onCheckedChange = { viewModel.toggleRememberLastFile(it) }
                )
            }
            Divider(color = colors.darkShadow.copy(alpha = 0.15f))
            Spacer(Modifier.height(24.dp))
            
            // DIAGNOSTICS SECTION
            Text("Hardware Diagnostics", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            NeumorphicCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                innerPadding = 16.dp,
                isPressed = true
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Test YubiKey connection by sending a ping challenge to the Driver overlay.",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )

            NeumorphicButton(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    viewModel.setDiagnosticResult("Awaiting physical hardware key...")
                    val dummyChallenge = "KeePassSD_Ping".toByteArray(Charsets.UTF_8)
                    val driverIntent = KeyDriverHelper.buildChallengeIntent(dummyChallenge)
                    if (driverIntent != null) {
                                val proxyIntent = Intent(context, KeyDriverProxyActivity::class.java).apply {
                                    putExtra(KeyDriverProxyActivity.EXTRA_DRIVER_INTENT, driverIntent)
                                }
                                context.startActivity(proxyIntent)
                    } else {
                        viewModel.setDiagnosticResult("Driver Not Installed! Please install KeePassDX Key Driver / ykDroid.")
                    }
                },
                innerPadding = 0.dp
            ) {
                Text("Test Hardware Key", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
                    
                    if (diagnosticResult.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NeumorphicCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 8.dp,
                            innerPadding = 12.dp,
                            isPressed = false,
                            backgroundColor = colors.background
                        ) {
                            Text(
                                diagnosticResult,
                                color = if (diagnosticResult.startsWith("Success")) Color(0xFF4CAF50) else colors.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // ABOUT SECTION
            Text("About", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            NeumorphicCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                innerPadding = 24.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = colors.accent
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "KeePassSD (Sender Device)",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Version 1.0.0-beta",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Designed & Developed by",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        "Flakeforever 🤝 AI",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceInfoDialog(info: String?, onDismiss: () -> Unit) {
    val colors = LocalNeumorphicColors.current
    
    // To avoid dimming, we wrap in a Box that covers the screen in the same stack or use Dialog with transparent properties
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // Full screen transparent container to cancel default dimming (or as much as possible via properties)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss, indication = null, interactionSource = remember { MutableInteractionSource() }),
            contentAlignment = Alignment.Center
        ) {
            // THE FLAT MINIMAL CARD
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .padding(24.dp)
                    .background(
                        color = colors.background, // Match overall tone
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .border(
                        width = 2.dp,
                        color = colors.darkShadow.copy(alpha = 0.8f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "BRIDGE INFO",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Divider(color = colors.darkShadow.copy(alpha = 0.15f), thickness = 1.dp)
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    if (info == null) {
                        CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
                    } else {
                        val parts = info.split("|")
                        val model = parts.getOrNull(0) ?: "Unknown"
                        val version = parts.getOrNull(1) ?: "N/A"
                        val status = parts.getOrNull(2) ?: "GUEST"
                        
                        // Model layout: Label on left, Value on right (supporting multi-line if needed)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("MODEL", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = model, 
                                color = colors.textPrimary, 
                                fontSize = 15.sp, 
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Divider(color = colors.darkShadow.copy(alpha = 0.05f), thickness = 1.dp)
                        
                        LightInfoRow("VERSION", "v$version")
                        LightInfoRow("SAFETY", status)
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.textPrimary),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text("CLOSE", color = colors.background, fontWeight = FontWeight.Bold) // Inverted for button
                    }
                }
            }
        }
    }
}

@Composable
fun LightInfoRow(label: String, value: String) {
    val colors = LocalNeumorphicColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
