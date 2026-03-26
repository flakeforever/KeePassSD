package com.channingchen.keepasssd

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Group
import org.signal.argon2.Argon2
import org.signal.argon2.Type
import org.signal.argon2.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val entry: app.keemobile.kotpass.models.Entry,
    val groupName: String,
    val standardIconId: Int,
    val customIconData: ByteArray?
)

data class VaultGroup(
    val name: String,
    val items: List<VaultItem>,
    val subGroups: List<VaultGroup>,
    val standardIconId: Int,
    val customIconData: ByteArray?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    init { instance = this }

    companion object {
        /** Weak global reference so KeyDriverProxyActivity can relay its result back. */
        var instance: MainViewModel? = null
            private set
    }

    // Bridge: KeyDriverProxyActivity posts here, UnlockScreen collects
    private val _hwKeyResult = MutableSharedFlow<Result<ByteArray?>>(extraBufferCapacity = 1)
    val hwKeyResult: SharedFlow<Result<ByteArray?>> = _hwKeyResult.asSharedFlow()

    /** Called by KeyDriverProxyActivity when the driver returns successfully (or with null) */
    fun onHardwareKeyResponse(response: ByteArray?) {
        viewModelScope.launch { 
            _hwKeyResult.emit(Result.success(response))
            if (_showSettings.value) {
                _diagnosticResult.value = if (response != null) {
                    "Success! Received signature: ${response.toHexString()}"
                } else {
                    "Hardware Key returned null or was cancelled."
                }
            }
        }
    }

    /** Called by KeyDriverProxyActivity when the driver is not found */
    fun onHardwareKeyError(message: String) {
        viewModelScope.launch { 
            _hwKeyResult.emit(Result.failure(Exception(message)))
            if (_showSettings.value) {
                _diagnosticResult.value = "Error: $message"
            }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private val bleManager = BleUartManager.getInstance()
    val isBleConnected = bleManager.isConnected
    val isBleSending = bleManager.isSending
    val deviceInfo = bleManager.deviceInfo

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _unlockState = MutableStateFlow<UnlockState>(UnlockState.Idle)
    val unlockState: StateFlow<UnlockState> = _unlockState

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    private val _diagnosticResult = MutableStateFlow<String>("")
    val diagnosticResult: StateFlow<String> = _diagnosticResult

    var database: KeePassDatabase? = null

    private val _rootGroup = MutableStateFlow<VaultGroup?>(null)
    val rootGroup: StateFlow<VaultGroup?> = _rootGroup

    private val prefs = application.getSharedPreferences("keepasssd_prefs", Context.MODE_PRIVATE)

    private val _rememberLastFile = MutableStateFlow(prefs.getBoolean("remember_last_file", true))
    val rememberLastFile: StateFlow<Boolean> = _rememberLastFile

    private val _databaseUriStr = MutableStateFlow(prefs.getString("last_database_uri", "") ?: "")
    val databaseUriStr: StateFlow<String> = _databaseUriStr

    private val _keyfileUriStr = MutableStateFlow(prefs.getString("last_keyfile_uri", "") ?: "")
    val keyfileUriStr: StateFlow<String> = _keyfileUriStr

    private val _useKeyfile = MutableStateFlow(prefs.getBoolean("use_keyfile", false))
    val useKeyfilePref: StateFlow<Boolean> = _useKeyfile

    private val _useHwKey = MutableStateFlow(prefs.getBoolean("use_hw_key", false))
    val useHwKeyPref: StateFlow<Boolean> = _useHwKey

    private val _hwKeyOption = MutableStateFlow(prefs.getString("hw_key_option", "None") ?: "None")
    val hwKeyOption: StateFlow<String> = _hwKeyOption

    fun toggleRememberLastFile(enabled: Boolean) {
        _rememberLastFile.value = enabled
        prefs.edit().putBoolean("remember_last_file", enabled).apply()
        if (!enabled) {
            prefs.edit()
                .putString("last_database_uri", "")
                .putString("last_keyfile_uri", "")
                .putBoolean("use_keyfile", false)
                .putBoolean("use_hw_key", false)
                .putString("hw_key_option", "None")
                .apply()
            _databaseUriStr.value = ""
            _keyfileUriStr.value = ""
            _useKeyfile.value = false
            _useHwKey.value = false
            _hwKeyOption.value = "None"
        }
    }

    fun saveUnlockConfig(dbUri: Uri, keyfileUri: Uri?, useKeyfile: Boolean, hwKey: String, useHwKey: Boolean) {
        if (_rememberLastFile.value) {
            prefs.edit()
                .putString("last_database_uri", dbUri.toString())
                .putString("last_keyfile_uri", keyfileUri?.toString() ?: "")
                .putBoolean("use_keyfile", useKeyfile)
                .putBoolean("use_hw_key", useHwKey)
                .putString("hw_key_option", hwKey)
                .apply()
            _databaseUriStr.value = dbUri.toString()
            _keyfileUriStr.value = keyfileUri?.toString() ?: ""
            _useKeyfile.value = useKeyfile
            _useHwKey.value = useHwKey
            _hwKeyOption.value = hwKey
        }
    }

    fun resetState() {
        _unlockState.value = UnlockState.Idle
        database = null
        _rootGroup.value = null
        _canUndo.value = false
    }
    
    fun setUnlockError(msg: String) {
        _unlockState.value = UnlockState.Error(msg)
    }

    fun toggleSettings(show: Boolean) {
        _showSettings.value = show
        if (!show) {
            _diagnosticResult.value = ""
        }
    }

    fun setDiagnosticResult(result: String) {
        _diagnosticResult.value = result
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
        hwResponse: ByteArray? = null,
        useKeyfile: Boolean = false,
        hwKey: String = "None",
        useHwKey: Boolean = false
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
                    
                    val bytes = stream.use { it.readBytes() }
                    database = KeePassDatabase.decode(
                        java.io.ByteArrayInputStream(bytes), 
                        credentials,
                        kdfProvider = NativeKdfProvider()
                    )
                    
                    val dbName = extractFileName(context, dbUri)
                    val customIcons = database!!.content.meta.customIcons
                    val rootGroupVal = database!!.content.group
                    
                    _rootGroup.value = extractGroups(rootGroupVal, dbName, customIcons).firstOrNull()
                }

                val dbName = extractFileName(context, dbUri)
                _unlockState.value = UnlockState.Success(dbName)
                saveUnlockConfig(dbUri, if (useKeyfile) keyfileUri else null, useKeyfile, hwKey, useHwKey)
            } catch (e: Exception) {
                e.printStackTrace()
                _unlockState.value = UnlockState.Error(e.message ?: "Failed to unlock database")
            }
        }
    }

    fun connectBle(context: Context) {
        bleManager.connect(context)
    }

    fun sendUsername(item: VaultItem) {
        bleManager.sendString("TXT:${item.username}\n")
        _canUndo.value = true
    }

    fun sendPassword(item: VaultItem) {
        val pass = item.entry.fields["Password"]?.content ?: ""
        bleManager.sendString("TXT:${pass}\n")
        _canUndo.value = true
    }

    fun sendTab() {
        bleManager.sendString("CMD:TAB\n")
        _canUndo.value = false // Per user request: TAB does not support UNDO UI activation
    }

    fun sendEnter() {
        bleManager.sendString("CMD:ENTER\n")
        _canUndo.value = false // Per user request: ENTER does not support UNDO UI activation
    }

    fun sendLock() {
        bleManager.sendString("CMD:LOCK\n")
        // Normally lock doesn't trigger UNDO in KPB logic (not mentioned)
    }

    fun fetchDeviceInfo() {
        bleManager.sendString("GET:INFO\n")
    }

    fun sendUndo() {
        if (_canUndo.value) {
            bleManager.sendString("CMD:UNDO\n")
            _canUndo.value = false
        }
    }

    private fun extractGroups(group: Group, parentName: String, customIcons: Map<UUID, app.keemobile.kotpass.models.CustomIcon>): List<VaultGroup> {
        val currentGroupName = group.name.ifEmpty { parentName }
        val groupCustomIcon = group.customIconUuid?.let { customIcons[it]?.data }
        
        val items = group.entries.map { entry ->
            val title = entry.fields["Title"]?.content ?: "Untitled"
            val username = entry.fields["UserName"]?.content ?: ""
            val url = entry.fields["URL"]?.content ?: ""
            val itemCustomIcon = entry.customIconUuid?.let { customIcons[it]?.data }
            VaultItem(title, username, url, entry, currentGroupName, entry.icon.ordinal, itemCustomIcon)
        }
        
        val subGroups = group.groups.flatMap { subgroup ->
            extractGroups(subgroup, currentGroupName, customIcons)
        }

        // Return a single list containing the current group as a VaultGroup object
        return listOf(VaultGroup(currentGroupName, items, subGroups, group.icon.ordinal, groupCustomIcon))
    }
}


class NativeKdfProvider : KdfProvider {
    override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
        return when (kdfParameters) {
            is KdfParameters.Argon2 -> {
                try {
                    val version = if (kdfParameters.version == 19u) Version.V13 else Version.V10
                    val type = when (kdfParameters.variant) {
                        KdfParameters.Argon2.Variant.Argon2d -> Type.Argon2d
                        KdfParameters.Argon2.Variant.Argon2id -> Type.Argon2id
                    }

                    val builder = Argon2.Builder(version)
                        .type(type)
                        .iterations(kdfParameters.iterations.toInt())
                        .memoryCostKiB((kdfParameters.memory / 1024uL).toInt())
                        .parallelism(kdfParameters.parallelism.toInt())
                        .hashLength(32)

                    val argon2 = builder.build()
                    val result = argon2.hash(compositeKey, kdfParameters.salt.toByteArray())
                    result.hash
                } catch (e: Throwable) {
                    throw Exception(e.message ?: "Argon2 Native Failure", e)
                }
            }
            is KdfParameters.Aes -> {
                transformAesKey(compositeKey, kdfParameters.seed.toByteArray(), kdfParameters.rounds)
            }
        }
    }

    private fun transformAesKey(key: ByteArray, seed: ByteArray, rounds: ULong): ByteArray {
        val bytes = key.copyOf()
        try {
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(seed, "AES")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)

            repeat(rounds.toInt()) {
                cipher.update(bytes, 0, 16, bytes, 0)
                cipher.update(bytes, 16, 16, bytes, 16)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
