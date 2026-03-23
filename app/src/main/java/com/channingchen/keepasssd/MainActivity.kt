package com.channingchen.keepasssd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channingchen.keepasssd.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeePassSDTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val colors = LocalNeumorphicColors.current
    var searchQuery by remember { mutableStateOf("") }
    
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
                horizontalArrangement = Arrangement.End
            ) {
                NeumorphicIconButton(
                    icon = Icons.Default.Settings,
                    onClick = { /* TODO */ }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Title
            Text(
                text = "KeePassSD",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
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
                            cursorColor = colors.accent
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                NeumorphicIconButton(
                    icon = Icons.Default.List,
                    onClick = { /* TODO */ }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Main Content Area (Selected Item Placeholder)
            NeumorphicCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cornerRadius = 24.dp
            ) {
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
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Action Buttons
            ActionButtons()
            
            Spacer(modifier = Modifier.height(24.dp))
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
            NeumorphicIconButton(
                icon = Icons.Default.Info,
                onClick = { /* TODO */ }
            )
            NeumorphicIconButton(
                icon = Icons.Default.Person,
                onClick = { /* TODO */ }
            )
            NeumorphicIconButton(
                icon = Icons.Default.Lock,
                onClick = { /* TODO */ }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            NeumorphicButton(
                onClick = { /* TODO */ },
                modifier = Modifier.weight(1f).height(64.dp)
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
                modifier = Modifier.weight(1f).height(64.dp)
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, device = "id:pixel_5")
@Composable
fun MainScreenPreview() {
    KeePassSDTheme {
        MainScreen()
    }
}