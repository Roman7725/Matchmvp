package com.matchmvp.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
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
    private var selectedAvatarIndex = 0

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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_UNDERAGE_BLOCKED, false)) {
            showBlockedScreen()
            return
        }

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

    private fun showBlockedScreen() {
        findViewById<View?>(resources.getIdentifier("joinScreen", "id", packageName))?.visibility = View.GONE
        findViewById<View?>(resources.getIdentifier("roomScreen", "id", packageName))?.visibility = View.GONE
        findViewById<View?>(resources.getIdentifier("blockedScreen", "id", packageName))?.visibility = View.VISIBLE
    }

    private fun calculateAge(day: Int, month: Int, year: Int): Int? {
        val today = java.util.Calendar.getInstance()
        val birth = java.util.Calendar.getInstance()
        if (year < 1900 || year > today.get(java.util.Calendar.YEAR)) return null
        if (month < 1 || month > 12) return null
        if (day < 1 || day > 31) return null
        birth.set(year, month - 1, day, 0, 0, 0)
        if (birth.get(java.util.Calendar.MONTH) != month - 1) return null

        var age = today.get(java.util.Calendar.YEAR) - birth.get(java.util.Calendar.YEAR)
        if (today.get(java.util.Calendar.DAY_OF_YEAR) < birth.get(java.util.Calendar.DAY_OF_YEAR)) {
            age--
        }
        return age
    }

    private fun setupUI() {
        val joinBtn = findViewById<Button?>(resources.getIdentifier("joinBtn", "id", packageName))
        val leaveBtn = findViewById<Button?>(resources.getIdentifier("leaveBtn", "id", packageName))
        val historyBtn = findViewById<Button?>(resources.getIdentifier("historyBtn", "id", packageName))
        val langBtn = findViewById<Button?>(resources.getIdentifier("langBtn", "id", packageName))

        setupAvatarPicker()

        joinBtn?.setOnClickListener {
            val nickInput = findViewById<EditText?>(resources.getIdentifier("nicknameInput", "id", packageName))
            val phoneInput = findViewById<EditText?>(resources.getIdentifier("phoneInput", "id", packageName))
            val emailInput = findViewById<EditText?>(resources.getIdentifier("emailInput", "id", packageName))
            val dayInput = findViewById<EditText?>(resources.getIdentifier("birthDayInput", "id", packageName))
            val monthInput = findViewById<EditText?>(resources.getIdentifier("birthMonthInput", "id", packageName))
            val yearInput = findViewById<EditText?>(resources.getIdentifier("birthYearInput", "id", packageName))

            val nick = nickInput?.text?.toString()?.trim().orEmpty()
            val day = dayInput?.text?.toString()?.trim()?.toIntOrNull()
            val month = monthInput?.text?.toString()?.trim()?.toIntOrNull()
            val year = yearInput?.text?.toString()?.trim()?.toIntOrNull()

            if (nick.isEmpty() || day == null || month == null || year == null) {
                Toast.makeText(this, getString(R.string.missing_fields_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val age = calculateAge(day, month, year)
            if (age == null) {
                Toast.makeText(this, getString(R.string.invalid_birthdate_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (age < 18) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_UNDERAGE_BLOCKED, true).apply()
                bleManager.stop()
                showBlockedScreen()
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
        bleManager.start(currentNickname, myAnonymousId, currentStatusCode, targetLikedUid, selectedAvatarIndex, contactPayload)
    }

    private fun onPeerDiscovered(peer: NearbyPeer) {
        if (peer.uid == myAnonymousId) return

        discoveredPeers[peer.uid] = peer

        if (peer.likedTargetUid == myAnonymousId && myLikes[peer.uid] == true) {
            if (!notifiedMatches.contains(peer.uid)) {
                notifiedMatches.add(peer.uid)
                triggerVibration()
                historyManager.saveMatch(peer.nickname, if (peer.contactInfo != "NONE") formatContactForDisplay(peer.contactInfo) else "NONE")

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
