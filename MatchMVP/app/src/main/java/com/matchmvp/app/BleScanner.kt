package com.matchmvp.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log

data class NearbyPeer(
    val anonymousId: String,
    val rssi: Int,       // сила сигнала — можно использовать для "теплее/холоднее"
    val lastSeenMs: Long
)

/**
 * Сканирует эфир в поиске анонимных ID других участников.
 * Не читает и не хранит ничего, кроме ID и силы сигнала.
 */
class BleScanner(
    private val adapter: BluetoothAdapter,
    private val onPeerFound: (NearbyPeer) -> Unit
) {
    companion object {
        private const val TAG = "BleScanner"
    }

    private val scanner get() = adapter.bluetoothLeScanner

    fun startScanning() {
        // ВАЖНО: фильтруем по service DATA (а не по списку service UUID),
        // потому что рекламодатель (BleAdvertiser) больше не включает
        // отдельный список UUID — это было лишним и раздувало пакет
        // сверх лимита в 31 байт. Маска из нулевых байт означает
        // "неважно, какое конкретно содержимое" — фильтруем только
        // по совпадению UUID сервиса.
        val emptyMask = ByteArray(6) { 0x00 }
        val emptyData = ByteArray(6) { 0x00 }

        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(BleAdvertiser.SERVICE_UUID), emptyData, emptyMask)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, callback)
    }

    fun stopScanning() {
        scanner?.stopScan(callback)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(BleAdvertiser.SERVICE_UUID)) ?: return

            val anonymousId = String(serviceData, Charsets.UTF_8).trim('\u0000')
            onPeerFound(
                NearbyPeer(
                    anonymousId = anonymousId,
                    rssi = result.rssi,
                    lastSeenMs = System.currentTimeMillis()
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Сканирование не удалось, код: $errorCode")
        }
    }
}
