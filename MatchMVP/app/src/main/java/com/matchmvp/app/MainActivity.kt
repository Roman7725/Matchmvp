package com.matchmvp.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

private const val EVENT_CODE = "pilot-event-1"

class MainActivity : AppCompatActivity() {

    private lateinit var repository: MatchRepository
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var adapter: PeerAdapter

    private val scope = MainScope()
    private val discoveredPeers = mutableMapOf<String, NearbyPeer>()
    private val knownMatches = mutableSetOf<String>()
    private val shownPhones = mutableSetOf<String>()
    
    private var myAnonymousId: String = UUID.randomUUID().toString().take(8)
    private var myNickname: String = ""
    private var myPhoneNumber: String = ""
    private var myEmail: String = ""
    private var myBadgeEnabled: Boolean = false
    private var matchesListener: ListenerRegistration? = null

    private var isEnglish: Boolean = false

    private lateinit var joinScreen: LinearLayout
    private lateinit var roomScreen: LinearLayout

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleAndFirestore()
        } else {
            val title = if (isEnglish) "Permissions required" else "Требуются разрешения"
            val msg = if (isEnglish) "Bluetooth and location permissions are required to discover nearby participants." else "Для поиска участников рядом требуются разрешения на Bluetooth и геолокацию."
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = MatchRepository(EVENT_CODE)

        joinScreen = findViewById(R.id.joinScreen)
        roomScreen = findViewById(R.id.roomScreen)
        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val badgeCheck = findViewById<CheckBox>(R.id.badgeCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val langBtn = findViewById<Button>(R.id.langBtn)
        val roomTitleTv = findViewById<TextView>(R.id.roomTitleTv)
        val recyclerView = findViewById<RecyclerView>(R.id.peersRecyclerView)

        val leaveBtnId = resources.getIdentifier("leaveBtn", "id", packageName)
        val leaveBtn: Button? = if (leaveBtnId != 0) findViewById(leaveBtnId) else null

        fun updateUiLanguage() {
            if (isEnglish) {
                nicknameInput.hint = "Nickname"
                phoneInput.hint = "Phone number"
                emailInput.hint = "Email (optional)"
                ageCheck.text = "I am 18 or older"
                badgeCheck.text = "Show community badge\n(visible only to those who also turned it on)"
                joinBtn.text = "JOIN BROADCAST"
                roomTitleTv.text = "Nearby"
                leaveBtn?.text = "Leave"
            } else {
                nicknameInput.hint = "Имя"
                phoneInput.hint = "Телефон"
                emailInput.hint = "Email (необязательно)"
                ageCheck.text = "Мне есть 18 лет"
                badgeCheck.text = "Показывать значок сообщества\n(виден только тем, у кого он тоже включён)"
                joinBtn.text = "ВОЙТИ В ЭФИР"
                roomTitleTv.text = "Кто рядом"
                leaveBtn?.text = "Выйти"
            }
        }

        langBtn.setOnClickListener {
            isEnglish = !isEnglish
            updateUiLanguage()
        }

        adapter = PeerAdapter { peer ->
            scope.launch {
                val realUid = repository.resolveUidForAnonymousId(peer.uid) ?: return@launch
                repository.sendLike(realUid)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        joinBtn.setOnClickListener {
            val nickname = nicknameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            if (nickname.isEmpty() || phone.isEmpty() || !ageCheck.isChecked) {
                val msg = if (isEnglish) "Please enter your nickname, phone number, and accept the terms." else "Пожалуйста, введите имя, телефон и подтвердите возраст."
                AlertDialog.Builder(this)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            myNickname = nickname
            myPhoneNumber = phone
            myEmail = email
            myBadgeEnabled = badgeCheck.isChecked
            
            joinScreen.visibility = LinearLayout.GONE
            roomScreen.visibility = LinearLayout.VISIBLE
            ensurePermissionsThenStart()
        }

        leaveBtn?.setOnClickListener {
            stopBleAndFirestore()
            roomScreen.visibility = LinearLayout.GONE
            joinScreen.visibility = LinearLayout.VISIBLE
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val notGranted = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            startBleAndFirestore()
        } else {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startBleAndFirestore() {
        scope.launch {
            try {
                repository.signInAnonymously()
                repository.registerParticipant(myNickname, myPhoneNumber, myEmail, myAnonymousId)
                repository.setCommunityVisible(myBadgeEnabled)
                matchesListener = repository.listenForMatches { matchId, otherUid -> onMatchFound(matchId, otherUid) }
            } catch (e: Exception) {
                val title = if (isEnglish) "Connection Error" else "Ошибка подключения"
                val msg = if (isEnglish) "Failed to connect to backend service.\n\nError details: ${e.javaClass.simpleName}: ${e.message}" else "Не удалось подключиться к серверу.\n\nДетали: ${e.javaClass.simpleName}: ${e.message}"
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
        }

        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter
        if (btAdapter == null) {
            val msg = if (isEnglish) "Bluetooth adapter not found. Nearby discovery is unavailable." else "Bluetooth не найден. Поиск устройств недоступен."
            AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        advertiser = BleAdvertiser(btAdapter)
        scanner = BleScanner(btAdapter) { peer -> onPeerDiscovered(peer) }

        val payload = "$myNickname:$myAnonymousId"
        advertiser.startAdvertising(payload)
        scanner.startScanning()
    }

    private fun stopBleAndFirestore() {
        if (::advertiser.isInitialized) advertiser.stopAdvertising()
        if (::scanner.isInitialized) scanner.stopScanning()
        matchesListener?.remove()
        matchesListener = null
        discoveredPeers.clear()
        peerNicknames.clear()
        peerBadges.clear()
        knownMatches.clear()
        shownPhones.clear()
        adapter.submitList(emptyList())
    }

    private val peerNicknames = mutableMapOf<String, String>()
    private val peerBadges = mutableMapOf<String, Boolean>()
    private var uiUpdateScheduled = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onPeerDiscovered(peer: NearbyPeer) {
        val parts = peer.anonymousId.split(":")
        val nickname = if (parts.size >= 2) parts[0] else "User"
        val anonId = if (parts.size >= 2) parts[1] else peer.anonymousId

        discoveredPeers[anonId] = NearbyPeer(anonId)
        peerNicknames[anonId] = nickname

        scheduleUiUpdate()
    }

    private fun scheduleUiUpdate() {
        if (uiUpdateScheduled) return
        uiUpdateScheduled = true
        mainHandler.postDelayed({
            uiUpdateScheduled = false
            val defaultName = if (isEnglish) "Nearby User" else "Участник рядом"
            val uiList = discoveredPeers.keys.map { anonymousId ->
                UiPeer(
                    uid = anonymousId,
                    avatarLabel = peerNicknames[anonymousId] ?: defaultName,
                    hasBadge = peerBadges[anonymousId] == true
                )
            }
            adapter.submitList(uiList)
        }, 500)
    }

    private fun onMatchFound(matchId: String, otherUid: String) {
        scope.launch {
            repository.listenForReveal(matchId, otherUid) { phone, email ->
                val key = "$matchId:${phone.orEmpty()}:${email.orEmpty()}"
                if (!shownPhones.contains(key)) {
                    shownPhones.add(key)

                    val contactText = buildString {
                        if (!phone.isNull_or_Empty()) append("Phone: $phone\n")
                        if (!email.isNullOrEmpty()) append("Email: $email")
                    }.trim()

                    if (contactText.isNotEmpty()) {
                        val alertTitle = if (isEnglish) "Contact Received! 🎉" else "Контакт получен! 🎉"
                        val copyLabel = if (isEnglish) "Copy" else "Скопировать"
                        val toastMsg = if (isEnglish) "Copied to clipboard" else "Скопировано в буфер обмена"

                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(alertTitle)
                                .setMessage(contactText)
                                .setPositiveButton(copyLabel) { _, _ ->
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Contact Info", contactText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        }

        if (knownMatches.contains(matchId)) return
        knownMatches.add(matchId)

        val title = if (isEnglish) "It's a Match! 🎉" else "Это Мэтч! 🎉"
        val msg = if (isEnglish) "You both liked each other! What contacts do you want to share?" else "Вы понравились друг другу! Какими контактами хотите поделиться?"
        
        val options = if (isEnglish) arrayOf("Phone number", "Email") else arrayOf("Номер телефона", "Email")
        val checkedItems = booleanArrayOf(true, myEmail.isNotEmpty())

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(if (isEnglish) "Share" else "Поделиться") { _, _ ->
                    val sharePhone = checkedItems[0]
                    val shareEmail = checkedItems[1]
                    scope.launch {
                        repository.revealContactsTo(matchId, sharePhone, shareEmail)
                    }
                }
                .setNegativeButton(if (isEnglish) "Not Now" else "Не сейчас", null)
                .show()
        }
    }

    private fun String?.isNull_or_Empty(): Boolean = this == null || this.isEmpty()

    override fun onDestroy() {
        super.onDestroy()
        stopBleAndFirestore()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
