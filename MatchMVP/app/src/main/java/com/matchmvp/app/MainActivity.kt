package com.matchmvp.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
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
    private lateinit var prefs: SharedPreferences
    private lateinit var imageLoader: ImageLoader

    private val scope = MainScope()
    private val discoveredPeers = mutableMapOf<String, NearbyPeer>()
    private val lastSeenTimes = mutableMapOf<String, Long>()
    private val blockedUsers = mutableSetOf<String>()
    
    private val knownMatches = mutableSetOf<String>()
    private val shownPhones = mutableSetOf<String>()
    
    private var myAnonymousId: String = UUID.randomUUID().toString().take(8)
    private var myNickname: String = ""
    private var myPhoneNumber: String = ""
    private var myEmail: String = ""
    private var myStatusEmoji: String = "🟢" // 🟢, 🟡, 🔴
    private var matchesListener: ListenerRegistration? = null

    private var isEnglish: Boolean = false

    private lateinit var joinScreen: LinearLayout
    private lateinit var roomScreen: LinearLayout
    private lateinit var radarStatusTv: TextView
    private lateinit var myAvatarPreview: ImageView

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleAndFirestore()
        } else {
            val title = if (isEnglish) "Permissions required" else "Требуются разрешения"
            val msg = if (isEnglish) "Bluetooth and location permissions are required." else "Требуются разрешения на Bluetooth и геолокацию."
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

        imageLoader = ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()

        repository = MatchRepository(EVENT_CODE)
        prefs = getSharedPreferences("match_history_prefs", Context.MODE_PRIVATE)

        joinScreen = findViewById(R.id.joinScreen)
        roomScreen = findViewById(R.id.roomScreen)
        radarStatusTv = findViewById(R.id.radarStatusTv)
        myAvatarPreview = findViewById(R.id.myAvatarPreview)
        
        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val emailInput = findViewById<EditText>(R.id.emailInput)
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val langBtn = findViewById<Button>(R.id.langBtn)
        val roomTitleTv = findViewById<TextView>(R.id.roomTitleTv)
        val statusLabelTv = findViewById<TextView>(R.id.statusLabelTv)
        val statusRadioGroup = findViewById<RadioGroup>(R.id.statusRadioGroup)
        val radioGreen = findViewById<RadioButton>(R.id.radioGreen)
        val radioYellow = findViewById<RadioButton>(R.id.radioYellow)
        val radioRed = findViewById<RadioButton>(R.id.radioRed)
        val recyclerView = findViewById<RecyclerView>(R.id.peersRecyclerView)

        val leaveBtnId = resources.getIdentifier("leaveBtn", "id", packageName)
        val leaveBtn: Button? = if (leaveBtnId != 0) findViewById(leaveBtnId) else null
        
        val historyBtnId = resources.getIdentifier("historyBtn", "id", packageName)
        val historyBtn: Button? = if (historyBtnId != 0) findViewById(historyBtnId) else null

        // Обновление аватарки DiceBear на лету при вводе имени
        fun updateAvatarPreview(name: String) {
            val seed = if (name.isEmpty()) "User" else name
            val avatarUrl = "https://api.dicebear.com/7.x/bottts/svg?seed=$seed"
            myAvatarPreview.load(avatarUrl, imageLoader)
        }

        updateAvatarPreview("")

        nicknameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAvatarPreview(s.toString().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fun updateUiLanguage() {
            if (isEnglish) {
                nicknameInput.hint = "Nickname"
                phoneInput.hint = "Phone number"
                emailInput.hint = "Email (optional)"
                ageCheck.text = "I am 18 or older"
                statusLabelTv.text = "Your status:"
                radioGreen.text = "🟢 Easy to approach"
                radioYellow.text = "🟡 Better text first"
                radioRed.text = "🔴 Just watching"
                joinBtn.text = "JOIN BROADCAST"
                roomTitleTv.text = "Nearby"
                radarStatusTv.text = "Scanning for nearby participants..."
                leaveBtn?.text = "Leave"
                historyBtn?.text = "History"
            } else {
                nicknameInput.hint = "Имя"
                phoneInput.hint = "Телефон"
                emailInput.hint = "Email (необязательно)"
                ageCheck.text = "Мне есть 18 лет"
                statusLabelTv.text = "Твой статус:"
                radioGreen.text = "🟢 Легко подойди"
                radioYellow.text = "🟡 Лучше сначала напиши"
                radioRed.text = "🔴 Пока только наблюдаю"
                joinBtn.text = "ВОЙТИ В ЭФИР"
                roomTitleTv.text = "Кто рядом"
                radarStatusTv.text = "Поиск участников рядом..."
                leaveBtn?.text = "Выйти"
                historyBtn?.text = "История"
            }
        }

        langBtn.setOnClickListener {
            isEnglish = !isEnglish
            updateUiLanguage()
        }

        adapter = PeerAdapter { peer ->
            scope.launch {
                val realUid = repository.resolveUidForAnonymousId(peer.uid) ?: return@launch
                if (blockedUsers.contains(realUid)) {
                    val msg = if (isEnglish) "User is blocked." else "Пользователь заблокирован."
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                repository.sendLike(realUid)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        joinBtn.setOnClickListener {
            val nickname = nicknameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val email = emailInput.text.toString().trim()

            val cleanPhone = phone.replace(Regex("[^0-9]"), "")
            if (cleanPhone.length < 7 || cleanPhone.length > 15) {
                val msg = if (isEnglish) "Please enter a valid phone number (7 to 15 digits)." else "Введите корректный номер телефона (от 7 до 15 цифр)."
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
                return@setOnClickListener
            }

            if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                val msg = if (isEnglish) "Please enter a valid email address." else "Введите корректный адрес email."
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
                return@setOnClickListener
            }

            if (nickname.isEmpty() || !ageCheck.isChecked) {
                val msg = if (isEnglish) "Please fill in all required fields and confirm age." else "Пожалуйста, заполните все обязательные поля и подтвердите возраст."
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
                return@setOnClickListener
            }

            myStatusEmoji = when (statusRadioGroup.checkedRadioButtonId) {
                R.id.radioYellow -> "🟡"
                R.id.radioRed -> "🔴"
                else -> "🟢"
            }

            myNickname = nickname
            myPhoneNumber = phone
            myEmail = email
            
            joinScreen.visibility = LinearLayout.GONE
            roomScreen.visibility = LinearLayout.VISIBLE
            ensurePermissionsThenStart()
        }

        leaveBtn?.setOnClickListener {
            stopBleAndFirestore()
            roomScreen.visibility = LinearLayout.GONE
            joinScreen.visibility = LinearLayout.VISIBLE
        }

        historyBtn?.setOnClickListener {
            showHistoryDialog()
        }

        startCleanupTask()
    }

    private fun saveContactToHistory(contactData: String) {
        val currentHistory = prefs.getStringSet("saved_contacts", mutableSetOf()) ?: mutableSetOf()
        val updatedHistory = HashSet(currentHistory)
        updatedHistory.add(contactData)
        prefs.edit().putStringSet("saved_contacts", updatedHistory).apply()
    }

    private fun showHistoryDialog() {
        val contactsSet = prefs.getStringSet("saved_contacts", emptySet()) ?: emptySet()
        val title = if (isEnglish) "Saved Contacts" else "История контактов"
        val emptyMsg = if (isEnglish) "No saved contacts yet." else "История пока пуста."

        if (contactsSet.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(emptyMsg)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val contactsList = contactsSet.toList()
        val items = contactsList.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                val selected = items[which]
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Contact Info", selected)
                clipboard.setPrimaryClip(clip)
                val msg = if (isEnglish) "Copied to clipboard!" else "Скопировано в буфер обмена!"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .setNeutralButton(if (isEnglish) "Clear History" else "Очистить") { _, _ ->
                prefs.edit().remove("saved_contacts").apply()
                val msg = if (isEnglish) "History cleared." else "История очищена."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter
        
        if (btAdapter == null || !btAdapter.isEnabled) {
            val msg = if (isEnglish) "Please enable Bluetooth to continue." else "Пожалуйста, включите Bluetooth для работы поиска."
            AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
            return
        }

        scope.launch {
            try {
                repository.signInAnonymously()
                repository.registerParticipant(myNickname, myPhoneNumber, myEmail, myAnonymousId)
                matchesListener = repository.listenForMatches { matchId, otherUid -> onMatchFound(matchId, otherUid) }
            } catch (e: Exception) {
                val title = if (isEnglish) "Connection Error" else "Ошибка подключения"
                val msg = if (isEnglish) "Failed to connect to server: ${e.message}" else "Не удалось подключиться к серверу: ${e.message}"
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
                }
                return@launch
            }
        }

        advertiser = BleAdvertiser(btAdapter)
        scanner = BleScanner(btAdapter) { peer -> onPeerDiscovered(peer) }

        // Передаем статус светофора вместе с именем
        val payload = "$myStatusEmoji $myNickname:$myAnonymousId"
        advertiser.startAdvertising(payload)
        scanner.startScanning()
    }

    private fun stopBleAndFirestore() {
        if (::advertiser.isInitialized) advertiser.stopAdvertising()
        if (::scanner.isInitialized) scanner.stopScanning()
        matchesListener?.remove()
        matchesListener = null
        discoveredPeers.clear()
        lastSeenTimes.clear()
        peerNicknames.clear()
        knownMatches.clear()
        shownPhones.clear()
        adapter.submitList(emptyList())
    }

    private val peerNicknames = mutableMapOf<String, String>()
    private var uiUpdateScheduled = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                val now = System.currentTimeMillis()
                val expired = lastSeenTimes.filter { now - it.value > 45000 }.keys
                for (id in expired) {
                    discoveredPeers.remove(id)
                    lastSeenTimes.remove(id)
                }
                if (expired.isNotEmpty()) {
                    scheduleUiUpdate()
                }
                mainHandler.postDelayed(this, 10000)
            }
        }, 10000)
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
                    hasBadge = false
                )
            }
            adapter.submitList(uiList)
        }, 500)
    }

    private fun triggerVibration() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(400)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun onMatchFound(matchId: String, otherUid: String) {
        if (blockedUsers.contains(otherUid)) return

        triggerVibration()

        scope.launch {
            repository.listenForReveal(matchId, otherUid) { phone, email ->
                val key = "$matchId:${phone.orEmpty()}:${email.orEmpty()}"
                if (!shownPhones.contains(key)) {
                    shownPhones.add(key)

                    val contactText = buildString {
                        if (!phone.isNullOrEmpty()) append("Тел: $phone ")
                        if (!email.isNullOrEmpty()) append("Email: $email")
                    }.trim()

                    if (contactText.isNotEmpty()) {
                        saveContactToHistory(contactText)

                        val alertTitle = if (isEnglish) "Contact Received! 🎉" else "Контакт получен! 🎉"
                        val copyLabel = if (isEnglish) "Copy" else "Скопировать"
                        val toastMsg = if (isEnglish) "Copied to clipboard & saved to History!" else "Скопировано в буфер и сохранено в Историю!"

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
                .setNeutralButton(if (isEnglish) "Report / Block" else "Пожаловаться") { _, _ ->
                    showReportDialog(otherUid)
                }
                .setNegativeButton(if (isEnglish) "Not Now" else "Не сейчас", null)
                .show()
        }
    }

    private fun showReportDialog(targetUid: String) {
        val title = if (isEnglish) "Report User" else "Пожаловаться на пользователя"
        val reasons = if (isEnglish) arrayOf("Inappropriate behavior", "Spam", "Fake profile") else arrayOf("Неадекватное поведение", "Спам", "Фейковый профиль")

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(reasons) { _, which ->
                val reason = reasons[which]
                blockedUsers.add(targetUid)
                discoveredPeers.remove(targetUid)
                scheduleUiUpdate()
                scope.launch {
                    repository.reportUser(targetUid, reason)
                }
                val msg = if (isEnglish) "User reported and blocked." else "Жалоба отправлена, пользователь заблокирован."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleAndFirestore()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
