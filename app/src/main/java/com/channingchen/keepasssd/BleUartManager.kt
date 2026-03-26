package com.channingchen.keepasssd

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleUartManager private constructor() {
    companion object {
        const val TAG = "BleUartManager"
        val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write to device
        val TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify from device
        const val DEVICE_NAME = "KPB-Bridge"

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

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending

    private val _deviceInfo = MutableStateFlow<String?>(null)
    val deviceInfo: StateFlow<String?> = _deviceInfo

    private var onWriteComplete: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(context: Context, onComplete: (Boolean) -> Unit = {}) {
        if (_isConnected.value) {
            onComplete(true)
            return
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return onComplete(false)

        val bondedDevices = adapter.bondedDevices
        val target = bondedDevices.find { it.name == DEVICE_NAME }

        if (target == null) {
            Log.e(TAG, "Device $DEVICE_NAME not found in bonded devices")
            onComplete(false)
            return
        }

        bluetoothGatt = target.connectGatt(context, false, gattCallback)
        // Keep callback to return connection status asynchronously or use StateFlow
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT, requesting MTU(128) and discovering services...")
                gatt.requestMtu(128)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT")
                _isConnected.value = false
                _deviceInfo.value = null
                txBuffer.setLength(0) // Clear buffer
                rxCharacteristic = null
                bluetoothGatt?.close()
                bluetoothGatt = null
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
                    Log.d(TAG, "Connected to UART Service RX & TX")
                    
                    // Enable Notification on TX
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                } else {
                    Log.e(TAG, "RX or TX Characteristic not found")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                Log.d(TAG, "Write complete, waiting for board OK response...")
                // BUTTONS STAY DISABLED (_isSending = true) UNTIL onCharacteristicChanged RECEIVES OK
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                val data = characteristic.value
                val newPart = data?.decodeToString() ?: ""
                txBuffer.append(newPart)

                // Process lines if \n is found
                if (txBuffer.contains("\n")) {
                    val fullLines = txBuffer.toString()
                    val lines = fullLines.split("\n")
                    
                    // The last part might be incomplete (no \n), keep it in the buffer
                    val isCompleteLineAtEnd = fullLines.endsWith("\n")
                    val linesToProcess = if (isCompleteLineAtEnd) lines else lines.dropLast(1)
                    
                    for (line in linesToProcess) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue
                        
                        Log.d(TAG, "KPB Line: $trimmedLine")
                        
                        if (trimmedLine.uppercase().contains("OK:DONE")) {
                            Log.d(TAG, "KPB Ack: Action Success")
                            _isSending.value = false
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                        } else if (trimmedLine.contains("INFO:", ignoreCase = true)) {
                            val rawInfo = trimmedLine.substringAfter("INFO:").trim()
                            Log.d(TAG, "KPB Info: $rawInfo")
                            _deviceInfo.value = rawInfo
                            _isSending.value = false
                            onWriteComplete?.invoke()
                            onWriteComplete = null
                        } else if (trimmedLine.uppercase().contains("ERR:")) {
                            Log.e(TAG, "KPB Error: $trimmedLine")
                            _isSending.value = false
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
    fun sendString(data: String, onFinish: () -> Unit = {}) {
        val gatt = bluetoothGatt ?: return onFinish()
        val char = rxCharacteristic ?: return onFinish()

        if (_isSending.value) return

        _isSending.value = true
        onWriteComplete = onFinish

        val bytes = data.toByteArray(Charsets.UTF_8)
        
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
            onFinish()
        } finally {
            // WIPE BUFFER
            bytes.fill(0)
        }
    }
}
