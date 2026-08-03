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
import android.widget.RadioGroup
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
    private val lastSeenTimes = ConcurrentHashMap<String, Long>()
    private val peerRssiMap = ConcurrentHashMap<String, Int>()
    
    // Сохраняем состояние лайков (uid -> liked)
    private val peerLikedMap = ConcurrentHashMap<String, Boolean>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    private var isEnglish = false

    // BLE Сканер и Вещатель
    private var bleScanner: BleScanner? = null
    private var bleAdvertiser: BleAdvertiser? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var myAnonymousId: String = UUID.randomUUID().toString().substring(0, 8)

    // RecyclerView и Adapter
    private lateinit var recyclerView: RecyclerView
    private val peerAdapter = PeerAdapter { peer ->
        peerLikedMap[peer.uid] = true
        scheduleUiUpdate()
    }

    private val uiUpdateRunnable = Runnable {
        val uiPeersList = discoveredPeers.keys.map { uid ->
            val rawName = peerNicknames[uid] ?: "User"
            val isLiked = peerLikedMap[uid] ?: false
            
            UiPeer(
                uid = uid,
                avatarLabel = rawName,
                liked = isLiked,
                hasBadge = false
            )
        }
        peerAdapter.submitList(uiPeersList)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = peerAdapter

        setupUI()
        startCleanupTask()
    }

    private fun setupUI() {
        val joinScreen = findViewById<LinearLayout>(R.id.joinScreen)
        val roomScreen = findViewById<LinearLayout>(R.id.roomScreen)

        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val leaveBtn = findViewById<Button>(R.id.leaveBtn)
        val langBtn = findViewById<Button>(R.id.langBtn)

        // 1. КНОПКА "ВОЙТИ В ЭФИР"
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

            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
            } else {
                enterRoom(nickname, joinScreen, roomScreen)
            }
        }

        // Кнопка "Выйти / Leave"
        leaveBtn?.setOnClickListener {
            roomScreen?.visibility = View.GONE
            joinScreen?.visibility = View.VISIBLE
            stopBleServices()
        }

        // 2. ПЕРЕКЛЮЧЕНИЕ ЯЗЫКА (RU / EN)
        langBtn?.setOnClickListener {
            toggleLanguage()
        }
    }

    private fun toggleLanguage() {
        isEnglish = !isEnglish

        val titleTv = findViewById<TextView>(R.id.titleTv)
        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val statusLabelTv = findViewById<TextView>(R.id.statusLabelTv)
        val radioGreen = findViewById<RadioButton>(R.id.radioGreen)
        val radioYellow = findViewById<RadioButton>(R.id.radioYellow)
        val radioRed = findViewById<RadioButton>(R.id.radioRed)
        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val roomTitleTv = findViewById<TextView>(R.id.roomTitleTv)
        val historyBtn = findViewById<Button>(R.id.historyBtn)
        val leaveBtn = findViewById<Button>(R.id.leaveBtn)
        val radarStatusTv = findViewById<TextView>(R.id.radarStatusTv)

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
        
        // Перерисовываем UI под новый язык
        scheduleUiUpdate()
    }

    private fun getSelectedStatusPrefix(): String {
        val radioGreen = findViewById<RadioButton>(R.id.radioGreen)
        val radioYellow = findViewById<RadioButton>(R.id.radioYellow)
        val radioRed = findViewById<RadioButton>(R.id.radioRed)

        return when {
            radioGreen?.isChecked == true -> "🟢"
            radioYellow?.isChecked == true -> "🟡"
            radioRed?.isChecked == true -> "🔴"
            else -> "🟢"
        }
    }

    private fun enterRoom(nickname: String, joinScreen: LinearLayout?, roomScreen: LinearLayout?) {
        joinScreen?.visibility = View.GONE
        roomScreen?.visibility = View.VISIBLE
        Toast.makeText(this, if (isEnglish) "Entered broadcast!" else "Вы вошли в эфир!", Toast.LENGTH_SHORT).show()

        val statusPrefix = getSelectedStatusPrefix()
        val fullBroadcastName = "$statusPrefix $nickname"

        startBleServices(fullBroadcastName)
    }

    private fun startBleServices(nickname: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, if (isEnglish) "Turn on Bluetooth!" else "Включите Bluetooth!", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // Запуск вещания своего имени и ID
            val payload = "$nickname:$myAnonymousId"
            bleAdvertiser = BleAdvertiser(adapter)
            bleAdvertiser?.startAdvertising(payload)

            // Запуск сканирования окружающих
            bleScanner = BleScanner(adapter) { peer ->
                onPeerDiscovered(peer)
            }
            bleScanner?.startScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopBleServices() {
        bleScanner?.stopScanning()
        bleAdvertiser?.stopAdvertising()
        bleScanner = null
        bleAdvertiser = null
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
                val joinScreen = findViewById<LinearLayout>(R.id.joinScreen)
                val roomScreen = findViewById<LinearLayout>(R.id.roomScreen)
                val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
                val nickname = nicknameInput?.text?.toString()?.trim().orEmpty()

                enterRoom(nickname, joinScreen, roomScreen)
            } else {
                Toast.makeText(this, "Необходим доступ к Bluetooth и Геолокации!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPeerDiscovered(peer: NearbyPeer) {
        val parts = peer.anonymousId.split(":")
        val nickname = if (parts.size >= 2) parts[0] else "User"
        val anonId = if (parts.size >= 2) parts[1] else peer.anonymousId

        if (blockedUsers.contains(anonId)) return

        discoveredPeers[anonId] = NearbyPeer(anonId)
        peerNicknames[anonId] = nickname
        lastSeenTimes[anonId] = System.currentTimeMillis()

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
                        peerRssiMap.remove(entry.key)
                        peerLikedMap.remove(entry.key)
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
