package com.matchmvp.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import java.nio.charset.StandardCharsets

data class NearbyPeer(val anonymousId: String)

class BleScanner(
    private val bluetoothAdapter: BluetoothAdapter,
    private val onPeerDiscovered: (NearbyPeer) -> Unit
) {

    private var scanner = bluetoothAdapter.bluetoothLeScanner
    private var callback: ScanCallback? = null

    fun startScanning() {
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(BleAdvertiser.SERVICE_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val serviceData = result.scanRecord?.getServiceData(BleAdvertiser.SERVICE_UUID) ?: return
                val payload = String(serviceData, StandardCharsets.UTF_8)
                if (payload.isNotEmpty()) {
                    onPeerDiscovered(NearbyPeer(payload))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (res in results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, res)
                }
            }

            override fun onScanFailed(errorCode: Int) {}
        }

        try {
            scanner?.startScan(listOf(filter), settings, callback)
        } catch (_: SecurityException) {}
    }

    fun stopScanning() {
        try {
            if (callback != null) {
                scanner?.stopScan(callback)
            }
        } catch (_: SecurityException) {}
        callback = null
    }
}
