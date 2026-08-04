package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB")

    private val blockedUsers = HashSet<String>()
    private val discoveredPeers = ConcurrentHashMap<String, NearbyPeer>()
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val peerStatuses = ConcurrentHashMap<String, String>()
    private val lastSeenTimes = ConcurrentHashMap<String, Long>()
    private val peerRssiMap = ConcurrentHashMap<String, Int>()
    
    private val peerLikedMap = ConcurrentHashMap<String, Boolean>() // Кого лайкнул Я
    private val likedMeSet = ConcurrentHashMap.newKeySet<String>() // Кто лайкнул МЕНЯ

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    private var isEnglish = false
    private var currentNickname = ""
    private var currentStatusCode = "GREEN"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private var myAnonymousId: String = UUID.randomUUID().toString().substring(0, 6)

    private var recyclerView: RecyclerView? = null
    private val peerAdapter = PeerAdapter { peer ->
        peerLikedMap[peer.uid] = true
        Toast.makeText(this, if (isEnglish) "Like sent!" else "Лайк отправлен!", Toast.LENGTH_SHORT).show()
        restartAdvertisingWithLike(peer.uid)
        scheduleUiUpdate()
    }

    private val uiUpdateRunnable = Runnable {
        val uiPeersList = discoveredPeers.keys.map { uid ->
            val rawName = peerNicknames[uid] ?: "User"
            val statusCode = peerStatuses[uid] ?: "GREEN"
            val isLikedByMe = peerLikedMap[uid] == true
            val isLikingMe = likedMeSet.contains(uid)
            val rssi = peerRssiMap[uid] ?: -70

            val statusHint = when (statusCode) {
                "GREEN" -> if (isEnglish) "🟢 Easy to approach" else "🟢 Легко подойди"
                "YELLOW" -> if (isEnglish) "🟡 Better text first" else "🟡 Лучше сначала напиши"
                "RED" -> if (isEnglish) "🔴 Just observing" else "🔴 Пока только наблюдаю"
                else -> if (isEnglish) "🟢 Easy to approach" else "🟢 Легко подойди"
            }

            val distanceText = when {
                rssi > -65 -> if (isEnglish) "Very close (~1-2m)" else "Очень близко (~1-2м)"
                rssi > -80 -> if (isEnglish) "Close (~3-5m)" else "Близко (~3-5м)"
                else -> if (isEnglish) "Nearby (>5m)" else "Недалеко (>5м)"
            }

            // СТРОГАЯ ЛОГИКА ОТОБРАЖЕНИЯ МАТЧА
            val displayName = when {
                // Только когда ОБА лайкнули
                isLikedByMe && isLikingMe -> "🔥 MATCH! $rawName"
                // Когда его лайкнули, но он ещё не лайкнул в ответ
                isLikingMe -> if (isEnglish) "❤️ $rawName (Liked you!)" else "❤️ $rawName (Лайкнул вас!)"
                // Когда он сам лайкнул, но второй ещё не ответил
                isLikedByMe -> if (isEnglish) "⭐ $rawName (Liked)" else "⭐ $rawName (Отправлен лайк)"
                else -> rawName
            }

            val fullLabel = "$displayName\n$statusHint\n📍 $distanceText"

            UiPeer(
                uid = uid,
                avatarLabel = fullLabel,
                liked = isLikedByMe,
                hasBadge = isLikingMe
            )
        }
        peerAdapter.submitList(uiPeersList)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val rvId = getLayoutResId("recyclerView")
            .takeIf { it != 0 }
            ?: getLayoutResId("peersRecyclerView")
                .takeIf { it != 0 }
            ?: getLayoutResId("peersList")

        if (rvId != 0) {
            recyclerView = findViewById(rvId)
            recyclerView?.apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = peerAdapter
            }
        }

        setupUI()
        startCleanupTask()
    }

    private fun getLayoutResId(name: String): Int {
        return resources.getIdentifier(name, "id", packageName)
    }

    private fun setupUI() {
        val joinScreen = findViewById<LinearLayout?>(getLayoutResId("joinScreen"))
        val roomScreen = findViewById<LinearLayout?>(getLayoutResId("roomScreen"))

        val nicknameInput = findViewById<EditText?>(getLayoutResId("nicknameInput"))
        val ageCheck = findViewById<CheckBox?>(getLayoutResId("ageCheck"))
        val joinBtn = findViewById<Button?>(getLayoutResId("joinBtn"))
        val leaveBtn = findViewById<Button?>(getLayoutResId("leaveBtn"))
        val langBtn = findViewById<Button?>(getLayoutResId("langBtn"))

        joinBtn?.setOnClickListener {
            val nickname = nicknameInput?.text?.toString()?.trim().orEmpty()

            if (nickname.isEmpty()) {
                Toast.makeText(this, if (isEnglish) "Enter nickname!" else "Введите имя!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ageCheck?.isChecked != true) {
                Toast.makeText(this, if (isEnglish) "Confirm 18+ check!" else "Подтвердите возрастной чекбокс (18+)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentNickname = nickname
            currentStatusCode = getSelectedStatusCode()

            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
            } else {
                enterRoom(nickname, joinScreen, roomScreen)
            }
        }

        leaveBtn?.setOnClickListener {
            roomScreen?.visibility = View.GONE
            joinScreen?.visibility = View.VISIBLE
            stopBleServices()
        }

        langBtn?.setOnClickListener {
            toggleLanguage()
        }
    }

    private fun getSelectedStatusCode(): String {
        val radioYellow = findViewById<RadioButton?>(getLayoutResId("radioYellow"))
        val radioRed = findViewById<RadioButton?>(getLayoutResId("radioRed"))

        return when {
            radioYellow?.isChecked == true -> "YELLOW"
            radioRed?.isChecked == true -> "RED"
            else -> "GREEN"
        }
    }

    private fun toggleLanguage() {
        isEnglish = !isEnglish

        val titleTv = findViewById<TextView?>(getLayoutResId("titleTv"))
        val nicknameInput = findViewById<EditText?>(getLayoutResId("nicknameInput"))
        val statusLabelTv = findViewById<TextView?>(getLayoutResId("statusLabelTv"))
        val radioGreen = findViewById<RadioButton?>(getLayoutResId("radioGreen"))
        val radioYellow = findViewById<RadioButton?>(getLayoutResId("radioYellow"))
        val radioRed = findViewById<RadioButton?>(getLayoutResId("radioRed"))
        val phoneInput = findViewById<EditText?>(getLayoutResId("phoneInput"))
        val emailInput = findViewById<EditText?>(getLayoutResId("emailInput"))
        val ageCheck = findViewById<CheckBox?>(getLayoutResId("ageCheck"))
        val joinBtn = findViewById<Button?>(getLayoutResId("joinBtn"))
        val roomTitleTv = findViewById<TextView?>(getLayoutResId("roomTitleTv"))
        val historyBtn = findViewById<Button?>(getLayoutResId("historyBtn"))
        val leaveBtn = findViewById<Button?>(getLayoutResId("leaveBtn"))
        val radarStatusTv = findViewById<TextView?>(getLayoutResId("radarStatusTv"))

        if (isEnglish) {
            titleTv?.text = "MATCH MVP"
            nicknameInput?.hint = "Nickname"
            statusLabelTv?.text = "Your status:"
            radioGreen?.text = "🟢 Easy to approach"
            radioYellow?.text = "🟡 Better text first"
            radioRed?.text = "🔴 Just observing"
            phoneInput?.hint = "Phone number"
            emailInput?.hint = "Email (optional)"
            ageCheck?.text = "I am 18 or older"
            joinBtn?.text = "JOIN BROADCAST"
            roomTitleTv?.text = "Nearby"
            historyBtn?.text = "History"
            leaveBtn?.text = "Leave"
            radarStatusTv?.text = "Searching for nearby peers..."
        } else {
            titleTv?.text = "MATCH MVP"
            nicknameInput?.hint = "Имя / Nickname"
            statusLabelTv?.text = "Твой статус:"
            radioGreen?.text = "🟢 Легко подойди"
            radioYellow?.text = "🟡 Лучше сначала напиши"
            radioRed?.text = "🔴 Пока только наблюдаю"
            phoneInput?.hint = "Телефон / Phone number"
            emailInput?.hint = "Email (опционально)"
            ageCheck?.text = "Мне есть 18 лет / I am 18 or older"
            joinBtn?.text = "ВОЙТИ В ЭФИР / JOIN BROADCAST"
            roomTitleTv?.text = "Кто рядом / Nearby"
            historyBtn?.text = "История"
            leaveBtn?.text = "Выйти / Leave"
            radarStatusTv?.text = "Поиск участников рядом..."
        }
        
        scheduleUiUpdate()
    }

    private fun enterRoom(nickname: String, joinScreen: LinearLayout?, roomScreen: LinearLayout?) {
        joinScreen?.visibility = View.GONE
        roomScreen?.visibility = View.VISIBLE
        startBleServices(nickname)
    }

    private fun startBleServices(nickname: String, targetLikedUid: String = "NONE") {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, if (isEnglish) "Turn on Bluetooth!" else "Включите Bluetooth!", Toast.LENGTH_LONG).show()
            return
        }

        stopBleServices()

        val safeNickname = if (nickname.length > 5) nickname.substring(0, 5) else nickname
        val payloadStr = "$safeNickname:$myAnonymousId:$currentStatusCode:$targetLikedUid"
        val payloadBytes = payloadStr.toByteArray(StandardCharsets.UTF_8)

        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), payloadBytes)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("BLE_TEST", "Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("BLE_TEST", "Advertising failed: $errorCode")
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) return

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

            override fun onScanFailed(errorCode: Int) {
                Log.e("BLE_TEST", "Scan failed: $errorCode")
            }
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleScanner?.startScan(null, scanSettings, scanCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return

        val payloadStr = String(serviceData, StandardCharsets.UTF_8)
        val rssi = result.rssi

        val parts = payloadStr.split(":")
        if (parts.size >= 2) {
            val nickname = parts[0]
            val anonId = parts[1]
            val status = if (parts.size >= 3) parts[2] else "GREEN"
            val likedTargetId = if (parts.size >= 4) parts[3] else "NONE"

            if (blockedUsers.contains(anonId) || anonId == myAnonymousId) return

            discoveredPeers[anonId] = NearbyPeer(anonId)
            peerNicknames[anonId] = nickname
            peerStatuses[anonId] = status
            lastSeenTimes[anonId] = System.currentTimeMillis()
            peerRssiMap[anonId] = rssi

            // Если кто-то лайкнул МОЙ ID
            if (likedTargetId == myAnonymousId) {
                likedMeSet.add(anonId)
            } else {
                likedMeSet.remove(anonId)
            }

            scheduleUiUpdate()
        }
    }

    private fun restartAdvertisingWithLike(targetLikedUid: String) {
        startBleServices(currentNickname, targetLikedUid)
    }

    private fun stopBleServices() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                advertiseCallback?.let { bleAdvertiser?.stopAdvertising(it) }
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                scanCallback?.let { bleScanner?.stopScan(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            advertiseCallback = null
            scanCallback = null
            bleAdvertiser = null
            bleScanner = null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val joinScreen = findViewById<LinearLayout?>(getLayoutResId("joinScreen"))
                val roomScreen = findViewById<LinearLayout?>(getLayoutResId("roomScreen"))
                val nicknameInput = findViewById<EditText?>(getLayoutResId("nicknameInput"))
                val nickname = nicknameInput?.text?.toString()?.trim().orEmpty()

                enterRoom(nickname, joinScreen, roomScreen)
            } else {
                Toast.makeText(this, "Необходим доступ к Bluetooth и Геолокации!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCleanupTask() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val iterator = lastSeenTimes.entries.iterator()

                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (currentTime - entry.value > 10000) {
                        discoveredPeers.remove(entry.key)
                        peerNicknames.remove(entry.key)
                        peerStatuses.remove(entry.key)
                        peerRssiMap.remove(entry.key)
                        peerLikedMap.remove(entry.key)
                        likedMeSet.remove(entry.key)
                        iterator.remove()
                    }
                }
                scheduleUiUpdate()
                mainHandler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun scheduleUiUpdate() {
        mainHandler.removeCallbacks(uiUpdateRunnable)
        mainHandler.postDelayed(uiUpdateRunnable, 300L)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleServices()
        mainHandler.removeCallbacks(uiUpdateRunnable)
    }
}
