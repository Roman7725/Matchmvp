package com.matchmvp.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import java.nio.charset.StandardCharsets
import java.util.UUID

class BleAdvertiser(private val bluetoothAdapter: BluetoothAdapter) {

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    companion object {
        // MatchMVP Service UUID
        val SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB"))
    }

    fun startAdvertising(dataPayload: String) {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val payloadBytes = dataPayload.toByteArray(StandardCharsets.UTF_8)

        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .addServiceData(SERVICE_UUID, payloadBytes)
            .setIncludeDeviceName(false)
            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
            override fun onStartFailure(errorCode: Int) {}
        }

        try {
            advertiser?.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {}
    }

    fun stopAdvertising() {
        try {
            if (callback != null) {
                advertiser?.stopAdvertising(callback)
            }
        } catch (_: SecurityException) {}
        callback = null
    }
}
