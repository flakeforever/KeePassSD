package com.channingchen.keepasssd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channingchen.keepasssd.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePassSDTheme {
                var currentScreen by remember { mutableStateOf("Unlock") }

                if (currentScreen == "Main") {
                    MainScreen(onNavigateToUnlock = { currentScreen = "Unlock" })
                } else {
                    UnlockScreen(onNavigateToMain = { currentScreen = "Main" })
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

data class MockItem(
    val title: String,
    val username: String,
    val url: String,
    val pass: String
)

val mockItems = listOf(
    MockItem("Gmail", "chadchen868@gmail.com", "https://mail.google.com", "password123"),
    MockItem("Gmail", "flake.chen@gmail.com", "https://mail.google.com", "password456")
)

@Composable
fun MainScreen(onNavigateToUnlock: () -> Unit = {}) {
    val colors = LocalNeumorphicColors.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<MockItem?>(null) }
    var showListMenu by remember { mutableStateOf(false) }

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
                NeumorphicCard(
                    modifier = Modifier.weight(1f).height(56.dp),
                    cornerRadius = 28.dp,
                    innerPadding = 0.dp
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search...", color = colors.textSecondary) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = colors.accent,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Box {
                    NeumorphicIconButton(
                        icon = Icons.Default.List,
                        onClick = { showListMenu = true }
                    )
                    
                    DropdownMenu(
                        expanded = showListMenu,
                        onDismissRequest = { showListMenu = false },
                        modifier = Modifier.background(colors.background)
                    ) {
                        Text(
                            text = "E-mail", 
                            fontWeight = FontWeight.Bold, 
                            color = colors.textSecondary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
                        mockItems.forEach { item ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(item.title, color = colors.textPrimary)
                                        Text(item.username, color = colors.textSecondary, fontSize = 12.sp)
                                    }
                                },
                                leadingIcon = { 
                                    Icon(Icons.Default.Email, null, tint = colors.textSecondary) 
                                },
                                onClick = { 
                                    selectedItem = item
                                    showListMenu = false 
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Main Content Area (Selected Item)
            NeumorphicCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp), // Fixed height to make empty/selected size exactly the same!
                cornerRadius = 24.dp
            ) {
                if (selectedItem != null) {
                    val item = selectedItem!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, tint = colors.accent, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                                Text(item.url, fontSize = 14.sp, color = colors.accent)
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Recessed Inverted Field
                        ReadOnlyField(value = item.username)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = colors.textSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No item selected",
                            color = colors.textSecondary,
                            fontSize = 18.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Bottom Instruction Text (Elevated right above the Action Buttons)
            Text(
                text = "Press below to send",
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action Buttons
            ActionButtons()
            
            Spacer(modifier = Modifier.height(24.dp))
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
fun ActionButtons() {
    val colors = LocalNeumorphicColors.current
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { /* TODO */ },
                modifier = Modifier.weight(1.5f).height(48.dp),
                innerPadding = 0.dp
            ) {
                Text("Username", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            NeumorphicButton(
                onClick = { /* TODO */ },
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
fun UnlockScreen(onNavigateToMain: () -> Unit = {}) {
    val colors = LocalNeumorphicColors.current
    val context = LocalContext.current

    var databaseFile by remember { mutableStateOf("") }
    val dbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            // Limit to .kdbx extension
            if (name.endsWith(".kdbx", ignoreCase = true)) {
                databaseFile = name
            } else {
                Toast.makeText(context, "Please select a .kdbx file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var password by remember { mutableStateOf("") }
    var usePassword by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    var keyfile by remember { mutableStateOf("") }
    var useKeyfile by remember { mutableStateOf(false) }
    var keyfileVisible by remember { mutableStateOf(false) }

    var hwKey by remember { mutableStateOf("None") }
    var useHwKey by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = extractFileName(context, uri)
            keyfile = name
            useKeyfile = name.isNotEmpty()
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
            val hwOptions = listOf("None", "YubiKey 5C Nano", "YubiKey 5 NFC")
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
                horizontalArrangement = Arrangement.End
            ) {
                val dbSelected = databaseFile.isNotEmpty()
                NeumorphicIconButton(
                    icon = Icons.Default.LockOpen,
                    onClick = { if (dbSelected) onNavigateToMain() },
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
                
                // Invisible overlay to capture clicks for file picking, 
                // leaving the right side (where the icon is) free for icon clicks.
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "id:pixel_5")
@Composable
fun MainScreenPreview() {
    KeePassSDTheme {
        MainScreen()
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "id:pixel_5")
@Composable
fun UnlockScreenPreview() {
    KeePassSDTheme {
        UnlockScreen()
    }
}