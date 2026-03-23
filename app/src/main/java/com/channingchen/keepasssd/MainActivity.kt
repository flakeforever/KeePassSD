package com.channingchen.keepasssd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.channingchen.keepasssd.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePassSDTheme {
                val viewModel: MainViewModel = viewModel()
                val unlockState by viewModel.unlockState.collectAsState()
                
                var currentScreen by remember { mutableStateOf("Unlock") }

                LaunchedEffect(unlockState) {
                    if (unlockState is UnlockState.Success) {
                        currentScreen = "Main"
                    }
                }

                if (currentScreen == "Main") {
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
    var currentViewedGroup by remember { mutableStateOf<VaultGroup?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val vaultGroups by viewModel.vaultGroups.collectAsState()

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
                    onClick = { /* TODO */ }
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
                        val allItems = vaultGroups.flatMap { it.items }
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
                cornerRadius = 24.dp
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
                        
                        ReadOnlyField(value = if(item.username.isEmpty()) "(No Username)" else item.username)
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
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Bottom Instruction Text
            Text(
                text = "Press below to send via HID",
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action Buttons
            ActionButtons(selectedItem = selectedItem)
            
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Bottom Sheet Drawer for Hierarchical Selection (Filters removed, Independent)
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { 
                    showBottomSheet = false
                    currentViewedGroup = null
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
                        if (currentViewedGroup != null) {
                            IconButton(onClick = { currentViewedGroup = null }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = currentViewedGroup!!.name,
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
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        if (currentViewedGroup == null) {
                            // SHOW GROUPS LIST
                            if (vaultGroups.isEmpty()) {
                                item {
                                    Text(
                                        "No groups found",
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                items(vaultGroups) { group ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { currentViewedGroup = group }
                                            .padding(horizontal = 24.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            KeePassIcon(
                                                standardIconId = group.standardIconId,
                                                customIconData = group.customIconData,
                                                tint = colors.textPrimary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(Modifier.width(16.dp))
                                            Text(
                                                text = group.name,
                                                color = colors.textPrimary,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Icon(Icons.Default.KeyboardArrowRight, null, tint = colors.textSecondary)
                                    }
                                    Divider(color = colors.darkShadow.copy(alpha=0.15f), modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        } else {
                            // SHOW ITEMS IN SELECTED GROUP (No Search Filter)
                            val groupItems = currentViewedGroup!!.items

                            if (groupItems.isEmpty()) {
                                item {
                                    Text(
                                        "Empty group",
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                items(groupItems) { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { 
                                                selectedItem = item
                                                showBottomSheet = false // closes drawer
                                                focusManager.clearFocus()
                                            }
                                            .padding(horizontal = 24.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        KeePassIcon(
                                            standardIconId = item.standardIconId,
                                            customIconData = item.customIconData,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(28.dp)
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
                                                    fontSize = 14.sp
                                                )
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
fun ActionButtons(selectedItem: VaultItem?) {
    val colors = LocalNeumorphicColors.current
    
    val userEnabled = selectedItem != null && selectedItem.username.isNotEmpty()
    val passEnabled = selectedItem != null && selectedItem.pass.isNotEmpty()
    val tabEnterEnabled = selectedItem != null

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { /* TODO */ },
                enabled = userEnabled,
                modifier = Modifier.weight(1.5f).height(48.dp),
                innerPadding = 0.dp
            ) {
                Text("Username", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            NeumorphicButton(
                onClick = { /* TODO */ },
                enabled = passEnabled,
                modifier = Modifier.weight(1.5f).height(48.dp),
                innerPadding = 0.dp
            ) {
                Text("Password", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { /* TODO */ },
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
            
            Spacer(modifier = Modifier.width(16.dp))
            
            NeumorphicButton(
                onClick = { /* TODO */ },
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
        }
    }
}

@Composable
fun UnlockScreen(viewModel: MainViewModel, unlockState: UnlockState) {
    val colors = LocalNeumorphicColors.current
    val context = LocalContext.current

    var databaseFile by remember { mutableStateOf("") }
    var databaseUri by remember { mutableStateOf<Uri?>(null) }
    
    val dbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            // Limit to .kdbx extension
            if (name.endsWith(".kdbx", ignoreCase = true)) {
                databaseFile = name
                databaseUri = uri
            } else {
                Toast.makeText(context, "Please select a .kdbx file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    var keyfile by remember { mutableStateOf("") }
    var keyfileUri by remember { mutableStateOf<Uri?>(null) }
    var useKeyfile by remember { mutableStateOf(false) }
    var keyfileVisible by remember { mutableStateOf(false) }

    var hwKey by remember { mutableStateOf("None") }
    var useHwKey by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            keyfile = name
            keyfileUri = uri
            useKeyfile = name.isNotEmpty()
        }
    }

    val hwKeyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val responseBytes = result.data?.getByteArrayExtra("response")
            if (responseBytes != null && databaseUri != null) {
                viewModel.unlockDatabase(context, databaseUri!!, password, if (useKeyfile) keyfileUri else null, responseBytes)
            } else {
                viewModel.setUnlockError("Hardware Key failed to return a valid response signature.")
            }
        } else {
            viewModel.setUnlockError("Hardware Key unlock cancelled.")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                // Ensures bottom content stays above keyboard
                .imePadding() 
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
                    onClick = { /* Settings */ }
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
                onSwitchChange = { usePassword = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

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

            // Pushes everything below it to the bottom
            Spacer(modifier = Modifier.weight(1f))

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
                                        val intent = android.content.Intent("android.yubikey.intent.action.CHALLENGE_RESPONSE")
                                        intent.putExtra("challenge", challengeBytes)
                                        intent.putExtra("purpose", "KeePassSD")
                                        
                                        try {
                                            hwKeyLauncher.launch(intent)
                                        } catch (e: android.content.ActivityNotFoundException) {
                                            try {
                                                intent.action = "net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE"
                                                hwKeyLauncher.launch(intent)
                                            } catch (e2: android.content.ActivityNotFoundException) {
                                                viewModel.setUnlockError("No Hardware Key Driver installed. Please install KeePassDX Key Driver or ykDroid.")
                                            }
                                        }
                                    }
                                }
                            } else {
                                viewModel.unlockDatabase(context, databaseUri!!, password, if (useKeyfile) keyfileUri else null)
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
                .height(64.dp),
            cornerRadius = 16.dp,
            innerPadding = 0.dp
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(label, color = colors.textSecondary) },
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
                visualTransformation = if (isObscured) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    if (showVisibilityToggle) {
                        IconButton(onClick = onToggleVisibility) {
                            Text(
                                text = if (isObscured) "🙈" else "👁",
                                fontSize = 20.sp
                            )
                        }
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Switch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accent,
                checkedTrackColor = colors.accent.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.background,
                uncheckedBorderColor = colors.darkShadow
            )
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
        Box(modifier = Modifier.weight(1f).height(64.dp)) {
            NeumorphicCard(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { expanded = true },
                cornerRadius = 16.dp,
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
                        color = if (selectedValue.isEmpty() || selectedValue == "None") colors.textSecondary else colors.textPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = colors.textSecondary
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
                        text = { Text(option, color = colors.textPrimary) },
                        onClick = {
                            onValueSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Switch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accent,
                checkedTrackColor = colors.accent.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.background,
                uncheckedBorderColor = colors.darkShadow
            )
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
                .height(64.dp),
            cornerRadius = 16.dp,
            innerPadding = 0.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                TextField(
                    value = selectedFile,
                    onValueChange = { },
                    readOnly = true,
                    placeholder = { Text(label, color = colors.textSecondary) },
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
                    visualTransformation = if (isObscured && selectedFile.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = {
                        if (selectedFile.isNotEmpty()) {
                            IconButton(onClick = onToggleVisibility) {
                                Text(
                                    text = if (isObscured) "🙈" else "👁",
                                    fontSize = 20.sp
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
        
        Switch(
            checked = switchChecked,
            onCheckedChange = onSwitchChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accent,
                checkedTrackColor = colors.accent.copy(alpha = 0.3f),
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.background,
                uncheckedBorderColor = colors.darkShadow
            )
        )
    }
}