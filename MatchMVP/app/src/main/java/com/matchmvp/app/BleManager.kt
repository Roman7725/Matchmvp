package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.nio.charset.StandardCharsets
import java.util.UUID

class BleManager(
    private val context: Context,
    private val onPeerDiscovered: (NearbyPeer) -> Unit
) {
    private val SERVICE_UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")
    private val ui = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisingSetCallback: AdvertisingSetCallback? = null
    private var scanCallback: ScanCallback? = null

    // ВРЕМЕННАЯ отладка: показывает ошибки Bluetooth прямо на экране.
    // Когда всё заработает, можно заменить тело функции на одну строку Log.d.
    private fun debug(msg: String) {
        Log.d("BLE_2_0", msg)
        ui.post { Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun supportsExtendedAdvertising(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported
    }

    // Статус кодируем одной буквой, чтобы экономить место в legacy-пакете (лимит 31 байт).
    private fun statusToCode(s: String): String = when (s) {
        "YELLOW" -> "Y"
        "RED" -> "R"
        else -> "G"
    }

    private fun codeToStatus(c: String): String = when (c) {
        "Y", "YELLOW" -> "YELLOW"
        "R", "RED" -> "RED"
        else -> "GREEN"
    }

    // Ник: без двоеточия (это разделитель) и не больше 8 байт в UTF-8 (кириллица = 2 байта на букву).
    private fun safeNick(raw: String): String {
        var nick = raw.replace(":", " ").trim()
        if (nick.length > 4) nick = nick.substring(0, 4)
        while (nick.isNotEmpty() && nick.toByteArray(StandardCharsets.UTF_8).size > 8) {
            nick = nick.dropLast(1)
        }
        return nick
    }

    private fun buildData(payload: String): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), payload.toByteArray(StandardCharsets.UTF_8))
            .build()

    fun start(myNickname: String, myUid: String, status: String, targetLikedUid: String, avatarId: Int, contact: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        stop()

        val useExtended = supportsExtendedAdvertising()

        // Короткая часть: помещается в legacy-пакет (макс. 24 байта данных)
        val basePayload = "${safeNick(myNickname)}:$myUid:${statusToCode(status)}:$targetLikedUid:$avatarId:"
        val legacyBytes = basePayload.toByteArray(StandardCharsets.UTF_8).size

        // Длинная часть: с контактом, только для extended
        val extendedPayload = basePayload + contact.take(40)

        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            debug("BLE: рекламирование не поддерживается на этом телефоне")
        }

        debug("BLE: режим ${if (useExtended) "extended" else "legacy"}, пакет $legacyBytes байт")

        if (useExtended) {
            startExtendedAdvertising(buildData(extendedPayload), buildData(basePayload))
        } else {
            startLegacyAdvertising(buildData(basePayload))
        }

        startScanning()
    }

    private fun startExtendedAdvertising(extData: AdvertiseData, legacyData: AdvertiseData) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        advertisingSetCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.d("BLE_2_0", "Extended ADV started")
                } else {
                    debug("BLE: extended не запустился (код $status), перехожу на legacy")
                    startLegacyAdvertising(legacyData)
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                Log.d("BLE_2_0", "Extended ADV stopped")
            }
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            debug("BLE: нет разрешения на рекламирование")
            return
        }

        try {
            bleAdvertiser?.startAdvertisingSet(params, extData, null, null, null, advertisingSetCallback)
        } catch (e: Exception) {
            Log.e("BLE_2_0", "Extended ADV exception", e)
            debug("BLE: extended ошибка, перехожу на legacy")
            startLegacyAdvertising(legacyData)
        }
    }

    private fun startLegacyAdvertising(advData: AdvertiseData) {
        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BLE_2_0", "Legacy ADV started")
            }

            override fun onStartFailure(errorCode: Int) {
                debug("BLE: legacy НЕ запустился, код $errorCode (1 = пакет слишком большой)")
            }
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            debug("BLE: нет разрешения на рекламирование")
            return
        }

        try {
            bleAdvertiser?.startAdvertising(advSettings, advData, advertiseCallback)
        } catch (e: Exception) {
            Log.e("BLE_2_0", "Legacy ADV exception", e)
            debug("BLE: legacy ошибка ${e.javaClass.simpleName}")
        }
    }

    private fun startScanning() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        val scanSettingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanSettingsBuilder.setLegacy(false)
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { parseScanResult(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { parseScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                debug("BLE: сканирование не запустилось, код $errorCode")
            }
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            debug("BLE: нет разрешения на сканирование")
            return
        }

        try {
            if (bleScanner == null) {
                debug("BLE: сканер недоступен (Bluetooth выключен?)")
                return
            }
            bleScanner?.startScan(null, scanSettingsBuilder.build(), scanCallback)
        } catch (e: Exception) {
            debug("BLE: сканирование ошибка ${e.javaClass.simpleName}")
        }
    }

    private fun parseScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        val payloadStr = String(serviceData, StandardCharsets.UTF_8)
        val parts = payloadStr.split(":", limit = 6)

        if (parts.size >= 2) {
            val peer = NearbyPeer(
                nickname = parts[0],
                uid = parts[1],
                status = if (parts.size >= 3) codeToStatus(parts[2]) else "GREEN",
                likedTargetUid = if (parts.size >= 4) parts[3] else "NONE",
                avatarId = if (parts.size >= 5) parts[4].toIntOrNull() ?: 0 else 0,
                contactInfo = if (parts.size >= 6 && parts[5].isNotEmpty()) parts[5] else "NONE",
                rssi = result.rssi
            )
            onPeerDiscovered(peer)
        }
    }

    fun stop() {
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiseCallback?.let { bleAdvertiser?.stopAdvertising(it) }
                advertisingSetCallback?.let { bleAdvertiser?.stopAdvertisingSet(it) }
            }
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                scanCallback?.let { bleScanner?.stopScan(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            advertiseCallback = null
            advertisingSetCallback = null
            scanCallback = null
        }
    }

    private fun hasPermission(perm: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
