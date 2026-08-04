package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
    
    private val peerLikedMap = ConcurrentHashMap<String, Boolean>() 
    private val likedMeSet = ConcurrentHashMap.newKeySet<String>() 
    private val peerContactsMap = ConcurrentHashMap<String, String>() 

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    private var isEnglish = false
    private var currentNickname = ""
    private var currentStatusCode = "GREEN"
    private var myPhone = ""
    private var myEmail = ""

    private var targetLikedUid = "NONE"
    private var contactPayload = "NONE"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private var myAnonymousId: String = UUID.randomUUID().toString().substring(0, 4)

    private var recyclerView: RecyclerView? = null
    private val peerAdapter = PeerAdapter { peer ->
        showContactChoiceDialog(peer.uid)
    }

    private lateinit var prefs: SharedPreferences

    private val uiUpdateRunnable = Runnable {
        val uiPeersList = discoveredPeers.keys.map { uid ->
            val rawName = peerNicknames[uid] ?: "User"
            val statusCode = peerStatuses[uid] ?: "GREEN"
            
            val isLikedByMe = peerLikedMap[uid] == true
            val isLikingMe = likedMeSet.contains(uid)
            val rssi = peerRssiMap[uid] ?: -70
            val contactInfo = peerContactsMap[uid] ?: prefs.getString("contact_$uid", null)

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

            val displayName = when {
                // ОБОЮДНЫЙ ЛАЙК: Показываем MATCH и настоящий контакт
                isLikedByMe && isLikingMe -> {
                    saveMatchToHistory(rawName, contactInfo)
                    val contactStr = if (!contactInfo.isNullOrEmpty() && contactInfo != "NONE") "\n📱 $contactInfo" else ""
                    "🔥 MATCH! $rawName$contactStr"
                }
                // Я отправил лайк (видно только мне)
                isLikedByMe -> if (isEnglish) "⭐ $rawName (Liked)" else "⭐ $rawName (Отправлен лайк)"
                // Тот кто меня лайкнул отображается стандартно до моего взаимного клика
                else -> rawName
            }

            val fullLabel = "$displayName\n$statusHint\n📍 $distanceText"

            UiPeer(
                uid = uid,
                avatarLabel = fullLabel,
                liked = isLikedByMe,
                hasBadge = isLikedByMe && isLikingMe
            )
        }
        peerAdapter.submitList(uiPeersList)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("match_history_prefs", Context.MODE_PRIVATE)

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
        val phoneInput = findViewById<EditText?>(getLayoutResId("phoneInput"))
        val emailInput = findViewById<EditText?>(getLayoutResId("emailInput"))
        val ageCheck = findViewById<CheckBox?>(getLayoutResId("ageCheck"))
        val joinBtn = findViewById<Button?>(getLayoutResId("joinBtn"))
        val leaveBtn = findViewById<Button?>(getLayoutResId("leaveBtn"))
        val historyBtn = findViewById<Button?>(getLayoutResId("historyBtn"))
        val langBtn = findViewById<Button?>(getLayoutResId("langBtn"))

        joinBtn?.setOnClickListener {
            val nickname = nicknameInput?.text?.toString()?.trim().orEmpty()
            myPhone = phoneInput?.text?.toString()?.trim().orEmpty()
            myEmail = emailInput?.text?.toString()?.trim().orEmpty()

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

        historyBtn?.setOnClickListener {
            showHistoryDialog()
        }

        langBtn?.setOnClickListener {
            toggleLanguage()
        }
    }

    private fun saveMatchToHistory(name: String, contact: String?) {
        val historySet = prefs.getStringSet("matches", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val entry = if (!contact.isNullOrEmpty() && contact != "NONE") "$name — $contact" else name
        if (!historySet.contains(entry)) {
            historySet.add(entry)
            prefs.edit().putStringSet("matches", historySet).apply()
        }
    }

    private fun showHistoryDialog() {
        val historySet = prefs.getStringSet("matches", emptySet()) ?: emptySet()
        val title = if (isEnglish) "Match History" else "История совпадений"

        if (historySet.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(if (isEnglish) "No matches yet." else "Совпадений пока нет.")
                .setPositiveButton("OK", null)
                .show()
        } else {
            val items = historySet.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, null)
                .setPositiveButton("OK", null)
                .setNeutralButton(if (isEnglish) "Clear" else "Очистить") { _, _ ->
                    prefs.edit().remove("matches").apply()
                    Toast.makeText(this, if (isEnglish) "History cleared" else "История очищена", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    // ИСПРАВЛЕННЫЙ ВЫБОР И ОТПРАВКА КОНТАКТА
    private fun showContactChoiceDialog(targetUid: String) {
        val optionsList = mutableListOf<String>()
        val valuesList = mutableListOf<String>()

        if (myPhone.isNotEmpty()) {
            optionsList.add(if (isEnglish) "Send Phone: $myPhone" else "Отправить телефон: $myPhone")
            valuesList.add(myPhone)
        }
        if (myEmail.isNotEmpty()) {
            optionsList.add(if (isEnglish) "Send Email: $myEmail" else "Отправить Email: $myEmail")
            valuesList.add(myEmail)
        }
        optionsList.add(if (isEnglish) "Don't send contact" else "Ничего не отправлять")
        valuesList.add("NONE")

        val title = if (isEnglish) "Share Contact with Like?" else "Поделиться контактом с лайком?"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(optionsList.toTypedArray()) { _, which ->
                // Фиксируем реальное строковое значение Email/Телефона
                contactPayload = valuesList[which]

                peerLikedMap[targetUid] = true
                targetLikedUid = targetUid

                Toast.makeText(this, if (isEnglish) "Like sent!" else "Лайк отправлен!", Toast.LENGTH_SHORT).show()
                
                startBleServices(currentNickname)
                scheduleUiUpdate()
            }
            .setNegativeButton(if (isEnglish) "Cancel" else "Отмена", null)
            .show()
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

    private fun startBleServices(nickname: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, if (isEnglish) "Turn on Bluetooth!" else "Включите Bluetooth!", Toast.LENGTH_LONG).show()
            return
        }

        stopBleServices()

        val safeNickname = if (nickname.length > 4) nickname.substring(0, 4) else nickname
        
        // Ограничиваем длинные почты до 12 символов, чтобы не переполнять байтовый лимит пакета BLE
        val safeContact = if (contactPayload.length > 12) contactPayload.substring(0, 12) else contactPayload 

        val payloadStr = "$safeNickname:$myAnonymousId:$currentStatusCode:$targetLikedUid:$safeContact"
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
                Log.d("BLE_TEST", "Advertising started")
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
            val contactInfo = if (parts.size >= 5) parts[4] else "NONE"

            if (blockedUsers.contains(anonId) || anonId == myAnonymousId) return

            discoveredPeers[anonId] = NearbyPeer(anonId)
            peerNicknames[anonId] = nickname
            peerStatuses[anonId] = status
            lastSeenTimes[anonId] = System.currentTimeMillis()
            peerRssiMap[anonId] = rssi

            if (likedTargetId == myAnonymousId) {
                likedMeSet.add(anonId)
                if (contactInfo != "NONE") {
                    peerContactsMap[anonId] = contactInfo
                    prefs.edit().putString("contact_$anonId", contactInfo).apply()
                }
            } else {
                likedMeSet.remove(anonId)
            }

            scheduleUiUpdate()
        }
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
                    if (currentTime - entry.value > 12000) {
                        discoveredPeers.remove(entry.key)
                        peerNicknames.remove(entry.key)
                        peerStatuses.remove(entry.key)
                        peerRssiMap.remove(entry.key)
                        peerLikedMap.remove(entry.key)
                        likedMeSet.remove(entry.key)
                        peerContactsMap.remove(entry.key)
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
