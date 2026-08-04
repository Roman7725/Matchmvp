package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.nio.charset.StandardCharsets
import java.util.UUID

class BleManager(
    private val context: Context,
    private val onPeerDiscovered: (NearbyPeer) -> Unit
) {
    private val SERVICE_UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun start(myNickname: String, myUid: String, status: String, targetLikedUid: String, contact: String) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        stop() // Очищаем старые колбэки при обновлении

        // Компактный формат пакета
        val safeNick = if (myNickname.length > 4) myNickname.substring(0, 4) else myNickname
        val safeContact = if (contact.length > 12) contact.substring(0, 12) else contact
        val payloadStr = "$safeNick:$myUid:$status:$targetLikedUid:$safeContact"
        val payloadBytes = payloadStr.toByteArray(StandardCharsets.UTF_8)

        // 1. Запуск Advertiser
        bleAdvertiser = adapter.bluetoothLeAdvertiser
        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), payloadBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BLE_2_0", "ADV Started: $payloadStr")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e("BLE_2_0", "ADV Failed: $errorCode")
            }
        }

        if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            bleAdvertiser?.startAdvertising(advSettings, advData, advertiseCallback)
        }

        // 2. Запуск Scanner
        bleScanner = adapter.bluetoothLeScanner
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { parseScanResult(it) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { parseScanResult(it) }
            }
        }

        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            bleScanner?.startScan(null, scanSettings, scanCallback)
        }
    }

    private fun parseScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        val payloadStr = String(serviceData, StandardCharsets.UTF_8)
        val parts = payloadStr.split(":")

        if (parts.size >= 2) {
            val peer = NearbyPeer(
                nickname = parts[0],
                uid = parts[1],
                status = if (parts.size >= 3) parts[2] else "GREEN",
                likedTargetUid = if (parts.size >= 4) parts[3] else "NONE",
                contactInfo = if (parts.size >= 5) parts[4] else "NONE",
                rssi = result.rssi
            )
            onPeerDiscovered(peer)
        }
    }

    fun stop() {
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiseCallback?.let { bleAdvertiser?.stopAdvertising(it) }
            }
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                scanCallback?.let { bleScanner?.stopScan(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            advertiseCallback = null
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
