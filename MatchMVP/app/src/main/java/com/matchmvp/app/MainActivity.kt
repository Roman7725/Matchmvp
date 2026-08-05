package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.nio.ByteBuffer
import java.util.*

class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val PERMISSION_REQUEST_CODE = 101

    private lateinit var nicknameInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var ageCheck: CheckBox
    private lateinit var badgeCheck: CheckBox
    private lateinit var joinBtn: Button
    private lateinit var langBtn: Button
    private lateinit var peersRecyclerView: RecyclerView

    private lateinit var peerAdapter: PeerAdapter
    private val peerList = mutableListOf<UiPeer>()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var isBroadcasting = false
    private var myShortId: Short = (1000..9999).random().toShort()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBluetooth()
        checkPermissions()
    }

    private fun initViews() {
        nicknameInput = findViewById(R.id.nicknameInput)
        phoneInput = findViewById(R.id.phoneInput)
        ageCheck = findViewById(R.id.ageCheck)
        badgeCheck = findViewById(R.id.badgeCheck)
        joinBtn = findViewById(R.id.joinBtn)
        langBtn = findViewById(R.id.langBtn)
        peersRecyclerView = findViewById(R.id.peersRecyclerView)

        peerAdapter = PeerAdapter { peer ->
            onPeerLiked(peer)
        }

        peersRecyclerView.layoutManager = LinearLayoutManager(this)
        peersRecyclerView.adapter = peerAdapter

        joinBtn.setOnClickListener {
            if (validateInputs()) {
                toggleBroadcast()
            }
        }

        langBtn.setOnClickListener {
            toggleLanguage()
        }
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun validateInputs(): Boolean {
        if (nicknameInput.text.isBlank() || phoneInput.text.isBlank() || !ageCheck.isChecked) {
            Toast.makeText(this, getString(R.string.missing_fields_message), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // --- 1. ПЕРЕКЛЮЧЕНИЕ ЯЗЫКА С RECREATE ---
    private fun toggleLanguage() {
        val currentLocale = resources.configuration.locales.get(0).language
        val newLanguage = if (currentLocale == "en") "ru" else "en"

        val locale = Locale(newLanguage)
        Locale.setDefault(locale)

        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        Toast.makeText(this, getString(R.string.msg_lang_changed), Toast.LENGTH_SHORT).show()
        recreate() // Полная перезагрузка UI для смены языка
    }

    // --- 2. СЖАТИЕ BLE ПАКЕТА (КОМПАКТНЫЙ PAYLOAD ДО 5 БАЙТ) ---
    private fun buildCompactPayload(senderId: Short, statusByte: Byte, targetId: Short): ByteArray {
        val buffer = ByteBuffer.allocate(5)
        buffer.putShort(senderId)     // 2 байта ID
        buffer.put(statusByte)        // 1 байт Статус
        buffer.putShort(targetId)     // 2 байта Target ID
        return buffer.array()
    }

    private fun toggleBroadcast() {
        if (!isBroadcasting) {
            startAdvertising()
            startScanning()
            joinBtn.text = "Stop"
            isBroadcasting = true
        } else {
            stopAdvertising()
            stopScanning()
            joinBtn.text = getString(R.string.join_button)
            isBroadcasting = false
        }
    }

    private fun startAdvertising() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        // Сжатый payload
        val payload = buildCompactPayload(myShortId, 0x01, 0x00)

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // КРИТИЧНО: Экономит до 15 байт в пакете!
            .addServiceData(ParcelUuid(SERVICE_UUID), payload)
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Toast.makeText(this@MainActivity, getString(R.string.err_ble_too_large), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner?.stopScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val rawData = scanRecord.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return

            if (rawData.size >= 5) {
                val buffer = ByteBuffer.wrap(rawData)
                val senderId = buffer.short
                val statusByte = buffer.get()
                val targetId = buffer.short

                val peerName = "User_$senderId"
                val distanceMeters = calculateDistance(result.rssi)

                runOnUiThread {
                    updatePeerInList(senderId, peerName, distanceMeters, statusByte)
                }
            }
        }
    }

    private fun calculateDistance(rssi: Int): String {
        val txPower = -59
        if (rssi == 0) return "📍 ?"
        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) {
            String.format(Locale.US, "📍 %.1fm", Math.pow(ratio, 10.0))
        } else {
            val dist = (0.89976) * Math.pow(ratio, 7.7095) + 0.111
            String.format(Locale.US, "📍 %.1fm", dist)
        }
    }

    private fun updatePeerInList(id: Short, name: String, distance: String, status: Byte) {
        val existingIndex = peerList.indexOfFirst { it.id == id.toInt() }
        val label = "$name ($distance)"

        if (existingIndex != -1) {
            val existing = peerList[existingIndex]
            peerList[existingIndex] = existing.copy(avatarLabel = label)
        } else {
            peerList.add(UiPeer(id = id.toInt(), avatarLabel = label, liked = false, hasBadge = false))
        }
        peerAdapter.submitList(peerList.toList())
    }

    private fun onPeerLiked(peer: UiPeer) {
        val index = peerList.indexOfFirst { it.id == peer.id }
        if (index != -1) {
            peerList[index] = peerList[index].copy(liked = true)
            peerAdapter.submitList(peerList.toList())

            // Показываем взаимность/мэтч при повторном клике или получении сигнала
            showMatchDialog(peer)
        }
    }

    private fun showMatchDialog(peer: UiPeer) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.match_title))
            .setMessage(getString(R.string.match_message))
            .setPositiveButton(getString(R.string.yes_button)) { _, _ ->
                val phoneMsg = String.format(getString(R.string.their_phone_format), phoneInput.text.toString())
                Toast.makeText(this, phoneMsg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.not_now_button), null)
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
