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
                        if (peer.contactInfo != "NONE") "$base\n📱 ${formatContactForDisplay(peer.contactInfo)}" else base
                    }
                    isLikedByMe -> getString(R.string.peer_liked_suffix, peer.nickname)
                    else -> peer.nickname
                }

                val avatarEmoji = AVATARS.getOrElse(peer.avatarId) { AVATARS[0] }

                UiPeer(
                    uid = peer.uid,
                    avatarLabel = "$avatarEmoji $title\n$statusHint\n📍 $distStr",
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

        if (myPhone.isNotEmpty()) {
            options.add(getString(R.string.contact_option_phone, myPhone))
            values.add("WA:" + digitsOnly(myPhone))
        }
        if (myEmail.isNotEmpty()) {
            options.add(getString(R.string.contact_option_email, myEmail))
            values.add("EMAIL:$myEmail")
        }
        options.add(getString(R.string.contact_option_none)); values.add("NONE")

        AlertDialog.Builder(this)
            .setTitle(R.string.contact_dialog_title)
            .setItems(options.toTypedArray()) { _, index ->
                contactPayload = values[index]
                targetLikedUid = targetUid
                myLikes[targetUid] = true

                bleManager.start(currentNickname, myAnonymousId, currentStatusCode, targetLikedUid, selectedAvatarIndex, contactPayload)
                updateUiList()
            }.show()
    }

    private fun digitsOnly(phone: String): String = phone.filter { it.isDigit() }

    private fun formatContactForDisplay(contact: String): String {
        return when {
            contact.startsWith("WA:") -> "WhatsApp: +${contact.removePrefix("WA:")}"
            contact.startsWith("EMAIL:") -> contact.removePrefix("EMAIL:")
            else -> contact
        }
    }

    private fun openContact(contact: String) {
        try {
            when {
                contact.startsWith("WA:") -> {
                    val digits = contact.removePrefix("WA:")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
                }
                contact.startsWith("EMAIL:") -> {
                    val email = contact.removePrefix("EMAIL:")
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, contact, Toast.LENGTH_LONG).show()
        }
    }

    private fun showMatchDialog(name: String, contact: String) {
        if (contact == "NONE") {
            AlertDialog.Builder(this)
                .setTitle(R.string.match_dialog_title)
                .setMessage(getString(R.string.match_dialog_message, name) + "\n\n" + getString(R.string.match_dialog_no_contact))
                .setPositiveButton(R.string.ok_button, null)
                .show()
            return
        }

        val actionLabel = if (contact.startsWith("WA:")) {
            getString(R.string.open_whatsapp_button)
        } else {
            getString(R.string.open_email_button)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.match_dialog_title)
            .setMessage(getString(R.string.match_dialog_message, name) + "\n\n" + getString(R.string.match_dialog_contact, formatContactForDisplay(contact)))
            .setPositiveButton(actionLabel) { _, _ -> openContact(contact) }
            .setNegativeButton(R.string.ok_button, null)
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

    private fun setupAvatarPicker() {
        val container = findViewById<LinearLayout?>(resources.getIdentifier("avatarPickerContainer", "id", packageName))
            ?: return
        container.removeAllViews()

        val avatarViews = mutableListOf<TextView>()

        AVATARS.forEachIndexed { index, emoji ->
            val tv = TextView(this).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                setPadding(20, 16, 20, 16)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.marginEnd = 12
                layoutParams = params
                background = if (index == selectedAvatarIndex) {
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3300E5FF"))
                } else {
                    null
                }
                setOnClickListener {
                    selectedAvatarIndex = index
                    avatarViews.forEachIndexed { i, v ->
                        v.background = if (i == index) {
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3300E5FF"))
                        } else {
                            null
                        }
                    }
                }
            }
            avatarViews.add(tv)
            container.addView(tv)
        }
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
        private const val KEY_UNDERAGE_BLOCKED = "underage_blocked"

        val AVATARS = listOf(
            "🦊", "🐻", "🦉", "🐺", "🐨", "🦁", "🐧", "🦅", "🐢", "🐬",
            "🏳️‍🌈", "🏳️‍⚧️", "🌈", "⭐"
        )
    }
}
