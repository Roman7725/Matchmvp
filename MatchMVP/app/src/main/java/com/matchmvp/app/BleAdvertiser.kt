package com.matchmvp.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Транслирует в эфир ТОЛЬКО анонимный ID — никаких имён, никаких
 * физических примет, ничего, что можно использовать для деанонимизации
 * без взаимного согласия через мэтч.
 */
class BleAdvertiser(private val adapter: BluetoothAdapter) {

    companion object {
        // Собственный UUID сервиса для этого приложения (сгенерирован один раз,
        // должен быть одинаковым в advertiser и scanner).
        val SERVICE_UUID: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        private const val TAG = "BleAdvertiser"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentCallback: AdvertiseCallback? = null

    fun startAdvertising(anonymousId: String) {
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising не поддерживается этим устройством")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        // ВАЖНО: у обычной (legacy) BLE-рекламы жёсткий лимит 31 байт на
        // весь пакет. 128-битный UUID сам по себе занимает 16 байт, плюс
        // служебные байты заголовков — поэтому payload идентификатора
        // должен быть очень коротким (6 байт), и мы НЕ добавляем отдельно
        // список service UUID (см. ниже) — иначе легко вылезти за лимит
        // и реклама просто не запустится на реальном устройстве.
        val idBytes = anonymousId.toByteArray(Charsets.UTF_8).copyOf(6)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), idBytes)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Не удалось начать advertising, код: $errorCode")
            }
        }
        currentCallback = callback
        advertiser?.startAdvertising(settings, data, callback)
    }

    fun stopAdvertising() {
        currentCallback?.let { advertiser?.stopAdvertising(it) }
    }
}
