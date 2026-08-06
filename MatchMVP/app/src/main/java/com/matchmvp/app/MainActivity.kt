package com.matchmvp.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val myAnonymousId: String = UUID.randomUUID().toString().substring(0, 4)
    private var currentNickname = ""
    private var currentStatusCode = "GREEN"
    private var myPhone = ""
    private var myEmail = ""
    private var targetLikedUid = "NONE"
    private var contactPayload = "NONE"

    private val discoveredPeers = ConcurrentHashMap<String, NearbyPeer>()
    private val myLikes = ConcurrentHashMap<String, Boolean>()
    private val notifiedMatches = HashSet<String>()

    private lateinit var bleManager: BleManager
    private lateinit var historyManager: HistoryManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

    private var recyclerView: RecyclerView? = null
    private val peerAdapter = PeerAdapter { peer -> showContactChoiceDialog(peer.uid) }

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val langCode = prefs.getString(KEY_LANG, defaultLangCode()) ?: defaultLangCode()
        super.attachBaseContext(applyLocale(newBase, langCode))
    }

    private fun defaultLangCode(): String {
        return if (Locale.getDefault().language == "ru") "ru" else "en"
    }

    private fun applyLocale(context: Context, langCode: String): Context {
        val locale = Locale(langCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private fun currentLangCode(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, defaultLangCode()) ?: defaultLangCode()
    }

    private fun toggleLanguage() {
        val newLang = if (currentLangCode() == "ru") "en" else "ru"
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, newLang).apply()

        Toast.makeText(
            this,
            if (newLang == "en") getString(R.string.lang_toast_en) else getString(R.string.lang_toast_ru),
            Toast.LENGTH_SHORT
        ).show()

        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        historyManager = HistoryManager(this)
        bleManager = BleManager(this) { peer -> onPeerDiscovered(peer) }

        val rvId = resources.getIdentifier("recyclerView", "id", packageName)
            .takeIf { it != 0 } ?: resources.getIdentifier("peersRecyclerView", "id", packageName)

        if (rvId != 0) {
            recyclerView = findViewById(rvId)
            recyclerView?.layoutManager = LinearLayoutManager(this)
            recyclerView?.adapter = peerAdapter
        }

        setupUI()
        startCleanupTask()
    }

    private fun setupUI() {
        val joinBtn = findViewById<Button?>(resources.getIdentifier("joinBtn", "id", packageName))
        val leaveBtn = findViewById<Button?>(resources.getIdentifier("leaveBtn", "id", packageName))
        val historyBtn = findViewById<Button?>(resources.getIdentifier("historyBtn", "id", packageName))
        val langBtn = findViewById<Button?>(resources.getIdentifier("langBtn", "id", packageName))

        joinBtn?.setOnClickListener {
            val nickInput = findViewById<EditText?>(resources.getIdentifier("nicknameInput", "id", packageName))
            val phoneInput = findViewById<EditText?>(resources.getIdentifier("phoneInput", "id", packageName))
            val emailInput = findViewById<EditText?>(resources.getIdentifier("emailInput", "id", packageName))
            val ageCheck = findViewById<CheckBox?>(resources.getIdentifier("ageCheck", "id", packageName))

            val nick = nickInput?.text?.toString()?.trim().orEmpty()
            if (nick.isEmpty() || ageCheck?.isChecked != true) {
                Toast.makeText(this, getString(R.string.missing_fields_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            currentNickname = nick
            myPhone = phoneInput?.text?.toString()?.trim().orEmpty()
            myEmail = emailInput?.text?.toString()?.trim().orEmpty()
            currentStatusCode = getSelectedStatusCode()

            if (hasPermissions()) {
                enterRoom()
            } else {
                requestPermissions()
            }
        }

        leaveBtn?.setOnClickListener {
            findViewById<View?>(resources.getIdentifier("roomScreen", "id", packageName))?.visibility = View.GONE
            findViewById<View?>(resources.getIdentifier("joinScreen", "id", packageName))?.visibility = View.VISIBLE
            bleManager.stop()
        }

        historyBtn?.setOnClickListener { showHistoryDialog() }
        langBtn?.setOnClickListener { toggleLanguage() }
    }

    private fun enterRoom() {
        findViewById<View?>(resources.getIdentifier("joinScreen", "id", packageName))?.visibility = View.GONE
        findViewById<View?>(resources.getIdentifier("roomScreen", "id", packageName))?.visibility = View.VISIBLE
        bleManager.start(currentNickname, myAnonymousId, currentStatusCode, targetLikedUid, contactPayload)
    }

    private fun onPeerDiscovered(peer: NearbyPeer) {
        if (peer.uid == myAnonymousId) return

        discoveredPeers[peer.uid] = peer

        if (peer.likedTargetUid == myAnonymousId && myLikes[peer.uid] == true) {
            if (!notifiedMatches.contains(peer.uid)) {
                notifiedMatches.add(peer.uid)
                triggerVibration()
                historyManager.saveMatch(peer.nickname, peer.contactInfo)

                mainHandler.post {
                    showMatchDialog(peer.nickname, peer.contactInfo)
                }
            }
        }
        updateUiList()
    }

    private fun updateUiList() {
        mainHandler.post {
            val uiList = discoveredPeers.values.map { peer ->
                val isLikedByMe = myLikes[peer.uid] == true
                val isLikingMe = peer.likedTargetUid == myAnonymousId

                val distMeters = calculateDistance(peer.rssi)
                val distStr = if (distMeters < 1.0) {
                    getString(R.string.distance_cm_format, (distMeters * 100).toInt())
                } else {
                    getString(R.string.distance_m_format, distMeters)
                }

                val statusHint = when (peer.status) {
                    "YELLOW" -> getString(R.string.status_hint_yellow)
                    "RED" -> getString(R.string.status_hint_red)
                    else -> getString(R.string.status_hint_green)
                }

                val title = when {
                    isLikedByMe && isLikingMe -> {
                        val base = getString(R.string.peer_match_prefix, peer.nickname)
                        if (peer.contactInfo != "NONE") "$base\n📱 ${peer.contactInfo}" else base
                    }
                    isLikedByMe -> getString(R.string.peer_liked_suffix, peer.nickname)
                    else -> peer.nickname
                }

                UiPeer(
                    uid = peer.uid,
                    avatarLabel = "$title\n$statusHint\n📍 $distStr",
                    liked = isLikedByMe,
                    hasBadge = isLikedByMe && isLikingMe
                )
            }
            peerAdapter.submitList(uiList)
        }
    }

    private fun showContactChoiceDialog(targetUid: String) {
        val options = mutableListOf<String>()
        val values = mutableListOf<String>()

        if (myPhone.isNotEmpty()) { options.add(getString(R.string.contact_option_phone, myPhone)); values.add(myPhone) }
        if (myEmail.isNotEmpty()) { options.add(getString(R.string.contact_option_email, myEmail)); values.add(myEmail) }
        options.add(getString(R.string.contact_option_none)); values.add("NONE")

        AlertDialog.Builder(this)
            .setTitle(R.string.contact_dialog_title)
            .setItems(options.toTypedArray()) { _, index ->
                contactPayload = values[index]
                targetLikedUid = targetUid
                myLikes[targetUid] = true

                bleManager.start(currentNickname, myAnonymousId, currentStatusCode, targetLikedUid, contactPayload)
                updateUiList()
            }.show()
    }

    private fun showMatchDialog(name: String, contact: String) {
        val contactText = if (contact != "NONE") {
            getString(R.string.match_dialog_contact, contact)
        } else {
            getString(R.string.match_dialog_no_contact)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.match_dialog_title)
            .setMessage(getString(R.string.match_dialog_message, name) + "\n\n" + contactText)
            .setPositiveButton(R.string.ok_button, null)
            .show()
    }

    private fun showHistoryDialog() {
        val matches = historyManager.getMatches()
        if (matches.isEmpty()) {
            Toast.makeText(this, getString(R.string.history_empty), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setItems(matches.toTypedArray(), null)
            .setNeutralButton(R.string.history_clear_button) { _, _ -> historyManager.clearHistory() }
            .setPositiveButton(R.string.ok_button, null)
            .show()
    }

    private fun calculateDistance(rssi: Int, txPower: Int = -59): Double {
        if (rssi == 0) return -1.0
        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) ratio.pow(10.0) else 0.89976 * ratio.pow(7.7095) + 0.111
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(500)
            }
        } catch (_: Exception) {}
    }

    private fun startCleanupTask() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val iterator = discoveredPeers.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeen > 12000) {
                        iterator.remove()
                    }
                }
                updateUiList()
                mainHandler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun getSelectedStatusCode(): String {
        val radioYellow = findViewById<RadioButton?>(resources.getIdentifier("radioYellow", "id", packageName))
        val radioRed = findViewById<RadioButton?>(resources.getIdentifier("radioRed", "id", packageName))
        return when {
            radioYellow?.isChecked == true -> "YELLOW"
            radioRed?.isChecked == true -> "RED"
            else -> "GREEN"
        }
    }

    private fun hasPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stop()
    }

    companion object {
        private const val PREFS_NAME = "matchmvp_settings"
        private const val KEY_LANG = "lang_code"
    }
}
