package com.matchmvp.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

/**
 * ВАЖНО: перед пилотным ивентом задай уникальный код мероприятия.
 * Участники с одинаковым EVENT_CODE видят друг друга, с разным — нет.
 */
private const val EVENT_CODE = "pilot-event-1"

private val AVATAR_POOL = listOf(
    "🦊 Лис", "🐻 Медведь", "🦉 Сова", "🐺 Волк", "🦋 Мотылёк",
    "🐨 Коала", "🦅 Орёл", "🐢 Черепаха", "🦁 Лев", "🐧 Пингвин"
)

class MainActivity : AppCompatActivity() {

    private lateinit var repository: MatchRepository
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var adapter: PeerAdapter

    private val scope = MainScope()
    private val discoveredPeers = mutableMapOf<String, NearbyPeer>()
    private val knownMatches = mutableSetOf<String>()
    private var myAvatarId: String = AVATAR_POOL.random()
    private var myAnonymousId: String = UUID.randomUUID().toString().take(8)
    private var myNickname: String = ""
    private var myPhoneNumber: String = ""
    private var myBadgeEnabled: Boolean = false
    private var matchesListener: ListenerRegistration? = null

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleAndFirestore()
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permissions_needed_title))
                .setMessage(getString(R.string.permissions_needed_message))
                .setPositiveButton(getString(R.string.ok_button), null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = MatchRepository(EVENT_CODE)

        val joinScreen = findViewById<LinearLayout>(R.id.joinScreen)
        val roomScreen = findViewById<LinearLayout>(R.id.roomScreen)
        val nicknameInput = findViewById<EditText>(R.id.nicknameInput)
        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val badgeCheck = findViewById<CheckBox>(R.id.badgeCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val recyclerView = findViewById<RecyclerView>(R.id.peersRecyclerView)

        adapter = PeerAdapter { peer ->
            scope.launch {
                // БАГ-ФИКС: раньше лайк уходил на "peer.uid", который на
                // самом деле был анонимным Bluetooth-ID, а не реальным
                // ID пользователя в базе — лайки и мэтчи никогда бы не
                // совпали. Сначала находим настоящий uid через сервер.
                val realUid = repository.resolveUidForAnonymousId(peer.uid) ?: return@launch
                repository.sendLike(realUid)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        joinBtn.setOnClickListener {
            val nickname = nicknameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            if (nickname.isEmpty() || phone.isEmpty() || !ageCheck.isChecked) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.missing_fields_message))
                    .setPositiveButton(getString(R.string.ok_button), null)
                    .show()
                return@setOnClickListener
            }
            myNickname = nickname
            myPhoneNumber = phone
            myBadgeEnabled = badgeCheck.isChecked
            joinScreen.visibility = LinearLayout.GONE
            roomScreen.visibility = LinearLayout.VISIBLE
            ensurePermissionsThenStart()
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()

        // БАГ-ФИКС: BLUETOOTH_ADVERTISE/SCAN/CONNECT существуют только
        // начиная с Android 12 (API 31). Раньше код запрашивал их на всех
        // версиях — на Android 8-11 система не знает эти разрешения,
        // диалог просто не появлялся, и пользователь навсегда застревал
        // на экране "нужны разрешения" без возможности их выдать.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // На Android 8-11 для BLE-сканирования обязательна геолокация
            // именно как runtime-разрешение (ограничение самой ОС).
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
                repository.registerParticipant(myAvatarId, myPhoneNumber, myAnonymousId)
                repository.setCommunityVisible(myBadgeEnabled)
                matchesListener = repository.listenForMatches { matchId, otherUid -> onMatchFound(matchId, otherUid) }
            } catch (e: Exception) {
                // ФИКС: раньше любая ошибка здесь (например, Firebase не
                // настроен — не включён Anonymous Auth, нет доступа к
                // Firestore, или google-services.json не на месте) молча
                // крашила всё приложение. Теперь вместо крэша показывается
                // понятное сообщение с текстом реальной ошибки.
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Не удалось подключиться")
                        .setMessage(
                            "Проверь, что Firebase настроен правильно " +
                            "(Anonymous Auth включён, Firestore создан, " +
                            "google-services.json на месте).\n\nТекст ошибки: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                        )
                        .setPositiveButton("Ок", null)
                        .show()
                }
                return@launch
            }
        }

        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter
        if (btAdapter == null) {
            AlertDialog.Builder(this)
                .setMessage("На этом устройстве не найден Bluetooth-адаптер — механика \"кто рядом\" не сможет работать.")
                .setPositiveButton("Ок", null)
                .show()
            return
        }

        advertiser = BleAdvertiser(btAdapter)
        scanner = BleScanner(btAdapter) { peer -> onPeerDiscovered(peer) }

        // Анонимный ID транслируется в открытый эфир как есть (он не несёт
        // личных данных), а связь "этот anonymousId = вот этот участник"
        // проверяется на сервере через resolveUidForAnonymousId() только
        // в момент лайка — так эфир остаётся анонимным, а лайки всё равно
        // корректно доходят до нужного человека в базе.
        advertiser.startAdvertising(myAnonymousId)
        scanner.startScanning()
    }

    private val peerAvatars = mutableMapOf<String, String>()
    private val peerBadges = mutableMapOf<String, Boolean>()
    private var uiUpdateScheduled = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onPeerDiscovered(peer: NearbyPeer) {
        discoveredPeers[peer.anonymousId] = peer
        val isNewPeer = peerAvatars.putIfAbsent(peer.anonymousId, AVATAR_POOL.random()) == null

        // Значок сообщества запрашиваем с сервера ТОЛЬКО если у нас самих
        // он включён — если выключен, даже не пытаемся ничего узнавать
        // про чужие значки, экономим запросы и не создаём лишний сигнал.
        if (isNewPeer && myBadgeEnabled) {
            scope.launch {
                val realUid = repository.resolveUidForAnonymousId(peer.anonymousId) ?: return@launch
                val hasBadge = repository.hasCommunityBadge(realUid)
                peerBadges[peer.anonymousId] = hasBadge
                scheduleUiUpdate()
            }
        }

        scheduleUiUpdate()
    }

    private fun scheduleUiUpdate() {
        // БАГ-ФИКС: BLE-сканирование может присылать по несколько сигналов
        // в секунду на одно и то же устройство — раньше список полностью
        // перерисовывался на КАЖДЫЙ такой сигнал, что заметно тормозило
        // интерфейс. Теперь обновляем экран не чаще одного раза в секунду.
        if (uiUpdateScheduled) return
        uiUpdateScheduled = true
        mainHandler.postDelayed({
            uiUpdateScheduled = false
            val uiList = discoveredPeers.keys.map { anonymousId ->
                UiPeer(
                    uid = anonymousId,
                    avatarLabel = peerAvatars.getValue(anonymousId),
                    hasBadge = peerBadges[anonymousId] == true
                )
            }
            adapter.submitList(uiList)
        }, 1000)
    }

    private fun onMatchFound(matchId: String, otherUid: String) {
        if (knownMatches.contains(matchId)) return
        knownMatches.add(matchId)

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.match_title))
                .setMessage(getString(R.string.match_message))
                .setPositiveButton(getString(R.string.yes_button)) { _, _ ->
                    scope.launch {
                        repository.revealPhoneTo(matchId, otherUid)
                        repository.listenForReveal(matchId, otherUid) { theirPhone ->
                            runOnUiThread {
                                AlertDialog.Builder(this@MainActivity)
                                    .setMessage(getString(R.string.their_phone_format, theirPhone))
                                    .setPositiveButton(getString(R.string.ok_button), null)
                                    .show()
                            }
                        }
                    }
                }
                .setNegativeButton(getString(R.string.not_now_button), null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::advertiser.isInitialized) advertiser.stopAdvertising()
        if (::scanner.isInitialized) scanner.stopScanning()
        // БАГ-ФИКС: раньше "слушатель" мэтчей из Firestore никогда не
        // отключался — при повторных заходах в приложение они бы
        // накапливались, вызывая дублирующиеся уведомления и постепенно
        // сажая батарею. Теперь явно отключаем всё при закрытии экрана.
        matchesListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
