package com.matchmvp.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private val blockedUsers = HashSet<String>()
    private val discoveredPeers = ConcurrentHashMap<String, NearbyPeer>()
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val peerStatuses = ConcurrentHashMap<String, String>() // GREEN, YELLOW, RED
    private val lastSeenTimes = ConcurrentHashMap<String, Long>()
    private val peerRssiMap = ConcurrentHashMap<String, Int>()
    
    // Состояние лайков
    private val peerLikedMap = ConcurrentHashMap<String, Boolean>()
    private val likedMeSet = ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    private var isEnglish = false
    private var currentNickname = ""
    private var currentStatusCode = "GREEN"

    // BLE Сканер и Вещатель
    private var bleScanner: BleScanner? = null
    private var bleAdvertiser: BleAdvertiser? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var myAnonymousId: String = UUID.randomUUID().toString().substring(0, 8)

    // RecyclerView и Adapter
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
            val isLikedByMe = peerLikedMap[uid] ?: false
            val isLikingMe = likedMeSet.contains(uid)
            val rssi = peerRssiMap[uid] ?: -70

            // 1. Статус готовности к общению
            val statusHint = when (statusCode) {
                "GREEN" -> if (isEnglish) "🟢 Easy to approach" else "🟢 Легко подойди"
                "YELLOW" -> if (isEnglish) "🟡 Better text first" else "🟡 Лучше сначала напиши"
                "RED" -> if (isEnglish) "🔴 Just observing" else "🔴 Пока только наблюдаю"
                else -> if (isEnglish) "🟢 Easy to approach" else "🟢 Легко подойди"
            }

            // 2. Расчёт расстояния
            val distanceText = when {
                rssi > -65 -> if (isEnglish) "Очень близко (~1-2m)" else "Очень близко (~1-2м)"
                rssi > -80 -> if (isEnglish) "Близко (~3-5m)" else "Близко (~3-5м)"
                else -> if (isEnglish) "Недалеко (>5m)" else "Недалеко (>5м)"
            }

            // 3. Формирование имени
            val displayName = when {
                isLikedByMe && isLikingMe -> "🔥 MATCH! $rawName"
                isLikingMe -> "❤️ $rawName (Лайкнул вас!)"
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
        val radioGreen = findViewById<RadioButton?>(getLayoutResId("radioGreen"))
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
        Toast.makeText(this, if (isEnglish) "Entered broadcast!" else "Вы вошли в эфир!", Toast.LENGTH_SHORT).show()

        startBleServices(nickname)
    }

    private fun startBleServices(nickname: String, targetLikedUid: String = "NONE") {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, if (isEnglish) "Turn on Bluetooth!" else "Включите Bluetooth!", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // 1. Сбрасываем текущие сервисы, чтобы сбросить кэш BLE и избежать наслоений
            stopBleServices()

            // 2. Ограничиваем имя 6 символами, так как кириллица занимает 2 байта на символ в UTF-8
            val safeNickname = if (nickname.length > 6) nickname.substring(0, 6) else nickname
            val payload = "$safeNickname:$myAnonymousId:$currentStatusCode:$targetLikedUid"
            
            bleAdvertiser = BleAdvertiser(adapter)
            bleAdvertiser?.startAdvertising(payload)

            bleScanner = BleScanner(adapter) { peer ->
                onPeerDiscovered(peer)
            }
            bleScanner?.startScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartAdvertisingWithLike(targetLikedUid: String) {
        startBleServices(currentNickname, targetLikedUid)
    }

    private fun stopBleServices() {
        try {
            bleScanner?.stopScanning()
            bleAdvertiser?.stopAdvertising()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bleScanner = null
            bleAdvertiser = null
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

    private fun extractRssiSafely(peer: NearbyPeer): Int {
        return try {
            val field = peer.javaClass.getDeclaredField("rssi")
            field.isAccessible = true
            (field.get(peer) as? Int) ?: -70
        } catch (e: Exception) {
            -70
        }
    }

    private fun onPeerDiscovered(peer: NearbyPeer) {
        // Разбираем пакет: NICK : ANON_ID : STATUS : LIKED_TARGET_ID
        val parts = peer.anonymousId.split(":")
        val nickname = if (parts.size >= 1) parts[0] else "User"
        val anonId = if (parts.size >= 2) parts[1] else peer.anonymousId
        val status = if (parts.size >= 3) parts[2] else "GREEN"
        val likedTargetId = if (parts.size >= 4) parts[3] else "NONE"

        if (blockedUsers.contains(anonId)) return

        discoveredPeers[anonId] = NearbyPeer(anonId)
        peerNicknames[anonId] = nickname
        peerStatuses[anonId] = status
        lastSeenTimes[anonId] = System.currentTimeMillis()
        peerRssiMap[anonId] = extractRssiSafely(peer)

        if (likedTargetId == myAnonymousId) {
            likedMeSet.add(anonId)
        }

        scheduleUiUpdate()
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
