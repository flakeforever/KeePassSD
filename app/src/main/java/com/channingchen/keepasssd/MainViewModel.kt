package com.channingchen.keepasssd

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.models.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class UnlockState {
    object Idle : UnlockState()
    object Loading : UnlockState()
    data class Success(val databaseName: String) : UnlockState()
    data class Error(val message: String) : UnlockState()
}

data class VaultItem(
    val title: String,
    val username: String,
    val url: String,
    val pass: String,
    val groupName: String,
    val standardIconId: Int,
    val customIconData: ByteArray?
)

data class VaultGroup(
    val name: String,
    val items: List<VaultItem>,
    val standardIconId: Int,
    val customIconData: ByteArray?
)

class MainViewModel : ViewModel() {
    private val _unlockState = MutableStateFlow<UnlockState>(UnlockState.Idle)
    val unlockState: StateFlow<UnlockState> = _unlockState

    var database: KeePassDatabase? = null

    private val _vaultGroups = MutableStateFlow<List<VaultGroup>>(emptyList())
    val vaultGroups: StateFlow<List<VaultGroup>> = _vaultGroups

    fun resetState() {
        _unlockState.value = UnlockState.Idle
        database = null
        _vaultGroups.value = emptyList()
    }

    fun unlockDatabase(context: Context, dbUri: Uri, password: String, keyfileUri: Uri?) {
        viewModelScope.launch {
            _unlockState.value = UnlockState.Loading
            try {
                withContext(Dispatchers.IO) {
                    val passValue = if (password.isNotEmpty()) {
                        EncryptedValue.fromString(password)
                    } else {
                        EncryptedValue.fromString("")
                    }
                    
                    val credentials = Credentials.from(passValue)
                    
                    val stream = context.contentResolver.openInputStream(dbUri)
                        ?: throw Exception("Cannot open database file")
                    
                    database = stream.use { KeePassDatabase.decode(it, credentials) }
                    
                    val rootGroup = database!!.content.group
                    val dbName = extractFileName(context, dbUri)
                    val customIcons = database!!.content.meta.customIcons
                    _vaultGroups.value = extractGroups(rootGroup, dbName, customIcons)
                }

                val dbName = extractFileName(context, dbUri)
                _unlockState.value = UnlockState.Success(dbName)
            } catch (e: Exception) {
                e.printStackTrace()
                _unlockState.value = UnlockState.Error(e.message ?: "Failed to unlock database")
            }
        }
    }

    private fun extractGroups(group: Group, parentName: String, customIcons: Map<UUID, app.keemobile.kotpass.models.CustomIcon>): List<VaultGroup> {
        val result = mutableListOf<VaultGroup>()
        val currentGroupName = group.name.ifEmpty { parentName }
        val groupCustomIcon = group.customIconUuid?.let { customIcons[it]?.data }
        
        val items = group.entries.map { entry ->
            val title = entry.fields["Title"]?.content ?: "Untitled"
            val username = entry.fields["UserName"]?.content ?: ""
            val password = entry.fields["Password"]?.content ?: ""
            val url = entry.fields["URL"]?.content ?: ""
            val itemCustomIcon = entry.customIconUuid?.let { customIcons[it]?.data }
            VaultItem(title, username, url, password, currentGroupName, entry.icon.ordinal, itemCustomIcon)
        }
        
        if (items.isNotEmpty()) {
            result.add(VaultGroup(currentGroupName, items, group.icon.ordinal, groupCustomIcon))
        }
        
        for (subgroup in group.groups) {
            result.addAll(extractGroups(subgroup, currentGroupName, customIcons))
        }
        return result
    }
}
