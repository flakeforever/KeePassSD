package com.channingchen.keepasssd

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

class BleUartManager private constructor() {
    companion object {
        const val TAG = "BleUartManager"
        // Flip to true for verbose BLE protocol tracing (per-line RX/TX,
        // scan results, crypto sizes). Off in production; warnings, errors
        // and key-sync lifecycle logs stay on regardless.
        const val DEBUG = false
        val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write to device
        val TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify from device
        const val DEVICE_NAME = "KPB"

        @Volatile
        private var instance: BleUartManager? = null

        fun getInstance(): BleUartManager {
            return instance ?: synchronized(this) {
                instance ?: BleUartManager().also { instance = it }
            }
        }
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var shouldReconnect = false
    private var savedContext: Context? = null
    private var savedAdapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private var psk: ByteArray? = null

    private fun dlog(msg: String) {
        if (DEBUG) Log.d(TAG, msg)
    }

    private val _hasPsk = MutableStateFlow(false)
    val hasPsk: StateFlow<Boolean> = _hasPsk

    private val _keyMismatch = MutableStateFlow(false)
    val keyMismatch: StateFlow<Boolean> = _keyMismatch
    private var sendTimeoutRunnable: Runnable? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _pskHex = MutableStateFlow<String>("")
    val pskHex: StateFlow<String> = _pskHex

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo

    // Key state reported by the FIRMWARE in its INFO line:
    //   true  = firmware already stores a PSK (state "ENCRYPTED")
    //   false = firmware never paired (state "FACTORY")
    //   null  = no INFO received yet
    private val _deviceHasKey = MutableStateFlow<Boolean?>(null)
    val deviceHasKey: StateFlow<Boolean?> = _deviceHasKey

    private var onWriteComplete: (() -> Unit)? = null
    private var writeOnTimeout: (() -> Unit)? = null

    // True while a KEY: (pairing) write is awaiting its ACK. Only a timeout
    // in this state indicates a real key mismatch (device ignored our KEY:).
    private var keyIsBeingWritten = false

    @SuppressLint("MissingPermission")
    fun connect(context: Context, onComplete: (Boolean) -> Unit = {}) {
        if (_isConnected.value) {
            onComplete(true)
            return
        }

        shouldReconnect = true
        savedContext = context
        startupCheckPsk(context)
        dlog("[CONN] connect(): psk in memory=" + pskMasked())

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return onComplete(false)
        savedAdapter = adapter

        // Fast path: check bonded devices first
        val bonded = adapter.bondedDevices?.find { it.name == DEVICE_NAME }
        if (bonded != null) {
            dlog("Found $DEVICE_NAME in bonded list, connecting")
            connectGatt(bonded)
            return
        }

        // No bond: scan for the device by name
        dlog("No bond found, scanning for $DEVICE_NAME")
        startScan()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                dlog("[CONN] GATT connected, psk=" + pskMasked())
                gatt.requestMtu(128)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dlog("[CONN] GATT disconnected, psk=" + pskMasked())
                _isConnected.value = false
                _deviceInfo.value = null
                _deviceHasKey.value = null
                txBuffer.setLength(0) // Clear buffer
                rxCharacteristic = null
                bluetoothGatt?.close()
                bluetoothGatt = null
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UART_SERVICE_UUID)
                rxCharacteristic = service?.getCharacteristic(RX_CHAR_UUID)
                val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                
                if (rxCharacteristic != null && txChar != null) {
                    _isConnected.value = true
                    dlog("[CONN] UART services ready, psk=" + pskMasked())
                    
                    // Enable Notification on TX
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    
                    // Probe immediately. Do NOT rely on the firmware's one-shot
                    // connect announcement (lost if our notification setup lags)
                    // and do NOT rely on MainActivity.onResume (it may have
                    // already fired before the link came up -> never re-fires).
                    handler.postDelayed({
                        if (_isConnected.value && rxCharacteristic != null && !_isSending.value) {
                            // INFO is fetched in PLAINTEXT on purpose: the firmware answers
                            // GET in both states, and a plaintext probe never deadlocks when
                            // our key is missing/different (an ENC probe would be silently
                            // dropped by a FACTORY firmware while stopping its announce loop).
                            dlog("[SYNC] post-connect probe GET:INFO plaintext (app psk=" + pskMasked() + ")")
                            sendString("GET:INFO\n", forcePlaintext = true)
                        }
                    }, 500)
                } else {
                    Log.e(TAG, "RX or TX Characteristic not found")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                dlog("[TX] Write complete, status=" + status)
                // BUTTONS STAY DISABLED (_isSending = true) UNTIL onCharacteristicChanged RECEIVES OK
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val data = characteristic.value
                val newPart = data?.decodeToString() ?: ""
                dlog("[RX] notify len=" + (data?.size ?: 0) + " part=[" + newPart + "]")
                txBuffer.append(newPart)

                // Process lines if \n is found
                if (txBuffer.contains("\n")) {
                    val fullLines = txBuffer.toString()
                    dlog("TX buffer newline: [" + fullLines + "]")
                    val lines = fullLines.split("\n")
                    
                    // The last part might be incomplete (no \n), keep it in the buffer
                    val isCompleteLineAtEnd = fullLines.endsWith("\n")
                    val linesToProcess = if (isCompleteLineAtEnd) lines else lines.dropLast(1)
                    
                    for (line in linesToProcess) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue

                        // Decode the line. Two dead-ends must be caught so the
                        // UI never hangs: (a) device replied ENCRYPTED but we have
                        // no PSK, (b) we have a PSK but it cannot decrypt the reply.
                        val decodedLine: String
                        val encMismatch: Boolean
                        if (trimmedLine.startsWith("ENC:")) {
                            if (psk == null) {
                                Log.w(TAG, "[KEY] Device replied ENCRYPTED but app has no PSK -> device has a key we lack (mismatch)")
                                encMismatch = true
                                decodedLine = ""
                            } else {
                                val encPart = trimmedLine.substringAfter("ENC:").trim()
                                val pt = decryptData(encPart)
                                if (pt == null) {
                                    Log.w(TAG, "[KEY] Cannot decrypt device reply with stored PSK -> wrong key (mismatch)")
                                    encMismatch = true
                                    decodedLine = ""
                                } else {
                                    encMismatch = false
                                    decodedLine = pt
                                }
                            }
                        } else {
                            encMismatch = false
                            decodedLine = trimmedLine
                        }

                        if (encMismatch) {
                            // Un-stick the UI: clear the pending write, notify the
                            // caller, then attempt self-heal.
                            cancelSendTimeout()
                            _isSending.value = false
                            keyIsBeingWritten = false
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                            _keyMismatch.value = false
                            if (psk != null) {
                                // Device has a DIFFERENT key than us. Push ours.
                                // The firmware accepts KEY: in all states (master=app).
                                Log.i(TAG, "[SYNC] key mismatch -> pushing app PSK to device")
                                val hex = psk!!.joinToString("") { "%02x".format(it) }
                                keyIsBeingWritten = true
                                sendString("KEY:$hex\n", forcePlaintext = true, onFinish = {
                                Log.i(TAG, "[SYNC] mismatch heal KEY pushed (awaiting ACK)")
                            }, onTimeout = {
                                Log.w(TAG, "[SYNC] heal push timed out")
                            })
                                armSendTimeout(10000)
                            } else {
                                // No local PSK (shouldn't happen after startupCheckPsk).
                                _keyMismatch.value = true
                            }
                            continue
                        }

                        dlog("KPB Line: $trimmedLine")
                        dlog("KPB Decoded: $decodedLine")

                        if (decodedLine.uppercase().startsWith("OK:")) {
                            dlog("KPB Ack: Action Success")
                            cancelSendTimeout()
                            _keyMismatch.value = false
                            _isSending.value = false
                            keyIsBeingWritten = false
                            // A KEY ACK means the firmware now stores our key locally.
                            if (decodedLine.contains("KEYSTORED", ignoreCase = true) ||
                                decodedLine.contains("KEYSYNCED", ignoreCase = true)) {
                                _deviceHasKey.value = true
                                Log.i(TAG, "[SYNC] firmware ACKed key -> device state = ENCRYPTED")
                            }
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                        } else if (decodedLine.contains("INFO:", ignoreCase = true)) {
                            val rawInfo = decodedLine.substringAfter("INFO:").trim()
                            dlog("[RX] INFO reply: " + rawInfo)
                            _deviceInfo.value = rawInfo
                            // Record the firmware's local key state (last INFO field).
                            val devState = rawInfo.substringAfterLast("|").trim().uppercase()
                            if (devState == "ENCRYPTED" || devState == "FACTORY") {
                                _deviceHasKey.value = (devState == "ENCRYPTED")
                                Log.i(TAG, "[SYNC] firmware key state: " + devState)
                            }
                            // If a KEY push is already in flight awaiting its ACK, an INFO
                            // line is NOT that ACK (the firmware re-announces INFO every 2s
                            // until it processes our KEY). Resolving the pending KEY here
                            // would falsely log "accepted" and disarm the mismatch timeout.
                            if (keyIsBeingWritten) {
                                dlog("[SYNC] INFO while KEY pending -> keep waiting for KEY ACK")
                                continue
                            }
                            // Clear the INFO write's completion
                            cancelSendTimeout()
                            _keyMismatch.value = false
                            _isSending.value = false
                            keyIsBeingWritten = false
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                            // ===== AUTO KEY SYNC =====
                            // Requirement: after INFO, the app always pushes its stored key.
                            //  - FACTORY  (no key on device): KEY: establishes the encrypted link.
                            //  - ENCRYPTED (device has a key): KEY: re-aligns/replaces it, then
                            //    all further traffic is AES-128-GCM.
                            // Harmless if keys already match (firmware overwrites same value).
                            if (psk != null) {
                                Log.i(TAG, "[SYNC] device=" + devState + " -> pushing app PSK")
                                val hex = psk!!.joinToString("") { "%02x".format(it) }
                                keyIsBeingWritten = true
                                sendString("KEY:$hex\n", forcePlaintext = true, onFinish = {
                                dlog("[SYNC] KEY accepted -> aligned")
                            }, onTimeout = {
                                Log.w(TAG, "[SYNC] KEY push timed out")
                            })
                                armSendTimeout(10000)
                            }
                        } else if (decodedLine.uppercase().contains("ERR:")) {
                            Log.e(TAG, "KPB Error: $decodedLine")
                            cancelSendTimeout()
                            _keyMismatch.value = false
                            _isSending.value = false
                            keyIsBeingWritten = false
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                        }
                    }

                    // Keep what's left
                    txBuffer.setLength(0)
                    if (!isCompleteLineAtEnd) {
                        txBuffer.append(lines.last())
                    }
                }
            }
        }
    }



    @SuppressLint("MissingPermission")
    fun sendString(data: String, onFinish: () -> Unit = {}, onTimeout: (() -> Unit)? = null, forcePlaintext: Boolean = false) {
        val gatt = bluetoothGatt ?: return onFinish()
        val char = rxCharacteristic ?: return onFinish()

        // If a previous send is still in flight (no ACK received), drop this
        // one and notify the caller. Prevents the pairing KEY write from being
        // clobbered by a racing fetchDeviceInfo (GET:INFO) write.
        if (_isSending.value) {
            dlog("sendString: busy, dropping")
            return onFinish()
        }

        _isSending.value = true
        onWriteComplete = onFinish
        writeOnTimeout = onTimeout

        // Arm 5s timeout to detect silent drop (key mismatch)
        armSendTimeout()

       val isEnc = psk != null && !forcePlaintext
       val toSend = if (isEnc) encryptData(data) else data
        dlog("[TX] sendString: cmdType=" + data.substringBefore(":") + " encrypted=" + isEnc + " wireLen=" + toSend.length)
       val bytes = toSend.toByteArray(Charsets.UTF_8)
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write characteristic: ${e.message}")
            _isSending.value = false
            keyIsBeingWritten = false
            onFinish()
        } finally {
            // WIPE BUFFER
            bytes.fill(0)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        _isConnected.value = false
        bluetoothGatt?.close()
        bluetoothGatt = null
        keyIsBeingWritten = false
        cancelSendTimeout()
        // Do NOT clear keyMismatch here: disconnect/reconnect (app backgrounding,
        // dropped GATT link) would hide an active mismatch banner. It is cleared
        // on any received response, or manually via clearKeyMismatch().
    }

    private fun scheduleReconnect() {
        stopScan()
        if (!shouldReconnect) return
        val adapter = savedAdapter ?: return
       handler.postDelayed({
           if (shouldReconnect && bluetoothGatt == null) {
                savedContext?.let { loadPsk(it) }
               val bonded = adapter.bondedDevices?.find { it.name == DEVICE_NAME }
                if (bonded != null) {
                    connectGatt(bonded)
                } else {
                    startScan()
                }
            }
        }, 2000)
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        if (bluetoothGatt != null) return
        val context = savedContext ?: return
        dlog("connectGatt: " + device.name)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = savedAdapter ?: return
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                dlog("scan: " + name + " rssi=" + result.rssi)
                if (name == DEVICE_NAME) {
                    stopScan()
                    connectGatt(result.device)
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>?) {}
        }
        scanCallback = cb
        adapter.bluetoothLeScanner?.startScan(cb)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        savedAdapter?.bluetoothLeScanner?.stopScan(cb)
    }

    // --- PSK Management ---

    fun hasPsk(): Boolean = psk != null

    fun armSendTimeout(ms: Long = 5000) {
        cancelSendTimeout()
        sendTimeoutRunnable = Runnable {
            if (_isSending.value) {
                _isSending.value = false
                val onTimeout = writeOnTimeout
                onWriteComplete = null
                writeOnTimeout = null
                if (keyIsBeingWritten) {
                    // Only a KEY (pairing) timeout can mean a real mismatch:
                    // the device already had a PSK and silently ignored KEY:.
                    // Plain command timeouts are ambiguous (BLE glitch, busy
                    // device) and would otherwise raise false banners.
                    _keyMismatch.value = true
                }
                Log.w(TAG, "Send timeout: device did not respond (keyWrite=" + keyIsBeingWritten + ")")
                keyIsBeingWritten = false
                onTimeout?.invoke()
            }
        }
        handler.postDelayed(sendTimeoutRunnable!!, ms)
    }

    fun cancelSendTimeout() {
        sendTimeoutRunnable?.let { handler.removeCallbacks(it) }
        sendTimeoutRunnable = null
        writeOnTimeout = null
    }

    fun clearKeyMismatch() {
        _keyMismatch.value = false
    }

    /** Generate a fresh 128-bit PSK and push it to the device in plaintext.
     *  The firmware accepts KEY: in BOTH states (factory and paired), and the
     *  key is persisted ONLY after the device ACKs, so memory/storage/device
     *  never diverge on a failed pairing.
     *  Callback receives the hex string on success, null on failure. */
    fun generatePsk(onComplete: (String?) -> Unit) {
        val key = ByteArray(16)
        SecureRandom().nextBytes(key)
        val hex = key.joinToString("") { String.format("%02x", it) }
        dlog("[PAIR] generatePsk: sending KEY in plaintext, hex len=" + hex.length)
        // Clear any in-flight write (e.g. a racing GET:INFO) so the KEY write
        // is the only pending one and cannot be busy-dropped.
        if (_isSending.value) {
            _isSending.value = false
            onWriteComplete = null
            writeOnTimeout = null
            cancelSendTimeout()
        }
        keyIsBeingWritten = true
        // KEY channel is plaintext BY PROTOCOL: the firmware accepts KEY: in
        // both states and ACKs in plaintext. Without forcePlaintext a local
        // PSK would encrypt this write; a device holding a different key drops
        // it silently -> 10s timeout (observed in the field).
        sendString("KEY:$hex\n", forcePlaintext = true, onFinish = {
            psk = key
            savePsk(hex)
            _pskHex.value = hex
            _hasPsk.value = true
            Log.i(TAG, "generatePsk: PSK established")
            onComplete(hex)
        }, onTimeout = {
            // No ACK: keep the previous key so memory/storage/device stay
            // aligned. (Early persistence here previously left storage and
            // memory holding DIFFERENT keys after a timeout.)
            Log.w(TAG, "generatePsk: no response from device")
            onComplete(null)
        })
        // Re-arm with a longer window: the device ACKs after a short delay and
        // the GATT link may need extra time after a write race. 5s was enough
        // for a single clean tap to report FAIL before the key was stored.
        armSendTimeout(10000)
    }

    fun wipePsk() {
        psk = null
        savedContext?.let { ctx ->
            ctx.getSharedPreferences("kpb_ble", Context.MODE_PRIVATE)
                .edit().remove("psk").apply()
        }
        _hasPsk.value = false
        dlog("wipePsk: PSK cleared")
    }

    private fun pskMasked(): String {
        val h = psk?.joinToString("") { "%02x".format(it) } ?: return "null"
        return if (h.length > 8) h.take(8) + "…(len=${h.length})" else h
    }

    /** App startup: log the stored PSK (full hex). If missing, generate and persist immediately. */
    fun startupCheckPsk(context: Context) {
        val prefs = context.getSharedPreferences("kpb_ble", Context.MODE_PRIVATE)
        val stored = prefs.getString("psk", null)
        if (stored != null) {
            psk = stored.hexToBytes()
            _pskHex.value = stored
            // Mirror hasPsk into the UI state flow: connect() uses this path,
            // and without it the dialog shows "APP KEY: None" and enables
            // PAIR KEY even though a key is loaded and the link is encrypted.
            _hasPsk.value = (psk != null)
            dlog("[BOOT] startup: stored PSK = " + pskMasked())
        } else {
            val key = ByteArray(16)
            SecureRandom().nextBytes(key)
            val hex = key.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("psk", hex).apply()
            psk = key
            _pskHex.value = hex
            _hasPsk.value = true
            dlog("[BOOT] startup: no stored PSK -> generated new one")
        }
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> ((this[2*i].digitToInt(16) shl 4) or this[2*i+1].digitToInt(16)).toByte() }

    private fun loadPsk(ctx: Context) {
        val hex = ctx.getSharedPreferences("kpb_ble", Context.MODE_PRIVATE)
             .getString("psk", null)
        dlog("[PSK] loadPsk: stored=" + (if (hex != null) "yes" else "null"))
        if (hex == null) {
            Log.w(TAG, "[PSK] loadPsk: NO PSK in storage (factory / wiped)")
            return
        }
        try {
             // NOTE: do NOT use String.toByte(16) here: it parses a SIGNED byte,
             // so any hex pair >= 0x80 (e.g. "bd") throws "Value out of range"
             // and silently wipes the PSK on every reconnect. hexToBytes() below
             // parses nibbles and casts, which handles the full 0x00-0xFF range.
             psk = hex.hexToBytes()
             dlog("[PSK] loadPsk: PSK read OK from storage")
         } catch (e: Exception) {
             Log.e(TAG, "[PSK] loadPsk: invalid stored PSK: " + e.message)
             psk = null
         }
        _hasPsk.value = (psk != null)
    }
    private fun savePsk(hex: String) {
        savedContext?.let { ctx ->
            ctx.getSharedPreferences("kpb_ble", Context.MODE_PRIVATE)
                .edit().putString("psk", hex).apply()
        }
        dlog("[PSK] savePsk: PSK persisted to storage")
    }

    // --- AES-128-GCM ---

   private fun encryptData(plaintext: String): String {
       val key = psk ?: return plaintext
       val nonce = ByteArray(12)
       SecureRandom().nextBytes(nonce)
       val cipher = Cipher.getInstance("AES/GCM/NoPadding")
       cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
       val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
       val combined = nonce + ct
        val result = "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        dlog("encryptData: combined=" + combined.size + " enc=" + result.length)
        return result
   }

   private fun decryptData(b64: String): String? {
       val key = psk ?: return null
       return try {
           val combined = Base64.decode(b64, Base64.NO_WRAP)
           val nonce = combined.copyOfRange(0, 12)
           val ct = combined.copyOfRange(12, combined.size)
           val cipher = Cipher.getInstance("AES/GCM/NoPadding")
           cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val pt = cipher.doFinal(ct)
            val res = String(pt, Charsets.UTF_8)
            dlog("decryptData: b64=" + b64.length + " combined=" + combined.size)
            res
       } catch (e: Exception) {
           Log.e(TAG, "decryptData: GCM auth failed")
           null
       }
   }

}
