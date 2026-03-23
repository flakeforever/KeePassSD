package com.channingchen.keepasssd

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.models.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
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

    fun setUnlockError(msg: String) {
        _unlockState.value = UnlockState.Error(msg)
    }

    fun requestYubiKeyChallenge(context: Context, uri: Uri, onResult: (ByteArray?) -> Unit) {
        viewModelScope.launch {
            _unlockState.value = UnlockState.Loading
            try {
                val challenge = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        extractMasterSeed(inputStream)
                    } ?: throw Exception("Failed to open database")
                }
                _unlockState.value = UnlockState.Idle
                onResult(challenge)
            } catch (e: Exception) {
                e.printStackTrace()
                _unlockState.value = UnlockState.Error("Failed to extract Challenge: ${e.message}")
                onResult(null)
            }
        }
    }

    private fun extractMasterSeed(inputStream: java.io.InputStream): ByteArray {
        val dis = java.io.DataInputStream(inputStream)
        val magicAndVersion = ByteArray(12)
        dis.readFully(magicAndVersion)

        // Little-endian Sig2 is at offsets 4, 5, 6, 7. 0x67 = KDBX4, 0x65 = KDBX3
        val isKdbx4 = magicAndVersion[4] == 0x67.toByte()

        val bb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        
        var masterSeed: ByteArray? = null
        var transformSeed: ByteArray? = null
        var kdfParameters: ByteArray? = null

        while (true) {
            val fieldId = dis.read()
            if (fieldId == -1 || fieldId == 0) break

            val len: Int
            if (isKdbx4) {
                val lBuf = ByteArray(4)
                dis.readFully(lBuf)
                bb.clear(); bb.put(lBuf); bb.flip()
                len = bb.int
            } else {
                val lBuf = ByteArray(2)
                dis.readFully(lBuf)
                bb.clear(); bb.put(lBuf); bb.putShort(0); bb.flip()
                len = bb.short.toInt()
            }

            val data = ByteArray(len)
            dis.readFully(data)

            if (fieldId == 4) masterSeed = data
            if (fieldId == 5) transformSeed = data
            if (fieldId == 11) kdfParameters = data
        }

        if (isKdbx4 && kdfParameters != null) {
            // For KDBX 4, the Challenge is the KDF Seed (key "S")
            val challenge = extractYubikeyChallengeFromCustomData(kdfParameters, "S")
            if (challenge != null) return challenge
        } else if (!isKdbx4 && transformSeed != null) {
            // For KDBX 3, the Challenge is the TransformSeed
            return transformSeed
        }

        return masterSeed ?: throw Exception("Master Seed/Challenge not found in Header")
    }

    private fun extractYubikeyChallengeFromCustomData(data: ByteArray, targetKey: String): ByteArray? {
        try {
            val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.short // Skip Version
            while (bb.hasRemaining()) {
                val type = bb.get().toInt() and 0xFF
                if (type == 0) break
                
                val keyLen = bb.int
                val keyBytes = ByteArray(keyLen)
                bb.get(keyBytes)
                val keyStr = String(keyBytes, Charsets.UTF_8)
                
                val valLen = bb.int
                if (keyStr == targetKey) {
                    val valBytes = ByteArray(valLen)
                    bb.get(valBytes)
                    return valBytes
                } else {
                    bb.position(bb.position() + valLen)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun unlockDatabase(
        context: Context, 
        dbUri: Uri, 
        password: String, 
        keyfileUri: Uri?,
        hwResponse: ByteArray? = null
    ) {
        viewModelScope.launch {
            _unlockState.value = UnlockState.Loading
            try {
                withContext(Dispatchers.IO) {
                    val passValue = if (password.isNotEmpty()) EncryptedValue.fromString(password) else EncryptedValue.fromString("")
                    
                    val credentials = if (hwResponse != null) {
                        if (password.isNotEmpty()) {
                            Credentials.from(passValue, hwResponse)
                        } else {
                            Credentials.from(hwResponse)
                        }
                    } else if (keyfileUri != null) {
                        val keyBytes = context.contentResolver.openInputStream(keyfileUri)?.use { it.readBytes() }
                            ?: throw Exception("Failed to read keyfile")
                        if (password.isNotEmpty()) {
                            Credentials.from(passValue, keyBytes)
                        } else {
                            Credentials.from(keyBytes)
                        }
                    } else if (password.isNotEmpty()) {
                        Credentials.from(passValue)
                    } else {
                        throw Exception("No credentials provided")
                    }
                    
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
