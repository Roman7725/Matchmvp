package com.matchmvp.app

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private val blockedUsers = HashSet<String>()
    private val discoveredPeers = ConcurrentHashMap<String, NearbyPeer>()
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val lastSeenTimes = ConcurrentHashMap<String, Long>()
    private val peerRssiMap = ConcurrentHashMap<String, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // 1. ЛОГИКА КНОПКИ "ВОЙТИ В ЭФИР"
        joinBtn?.setOnClickListener {
            val nickname = nicknameInput?.text?.toString()?.trim().orEmpty()

            if (nickname.isEmpty()) {
                Toast.makeText(this, "Введите имя!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ageCheck?.isChecked != true) {
                Toast.makeText(this, "Подтвердите возрастной чекбокс (18+)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Успешный вход -> переключаем экран входа на экран комнаты
            joinScreen?.visibility = View.GONE
            roomScreen?.visibility = View.VISIBLE
            Toast.makeText(this, "Вы вошли в эфир!", Toast.LENGTH_SHORT).show()
        }

        // Кнопка "Выйти / Leave"
        leaveBtn?.setOnClickListener {
            roomScreen?.visibility = View.GONE
            joinScreen?.visibility = View.VISIBLE
        }

        // 2. ЛОГИКА КНОПКИ ПЕРЕКЛЮЧЕНИЯ ЯЗЫКА (EN / RU)
        langBtn?.setOnClickListener {
            val currentLang = resources.configuration.locales.get(0).language
            val newLang = if (currentLang == "ru") "en" else "ru"
            setAppLanguage(newLang)
        }
    }

    private fun setAppLanguage(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)

        baseContext.resources.updateConfiguration(
            config,
            baseContext.resources.displayMetrics
        )

        // Перезапускаем экран для моментального обновления языка UI
        recreate()
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
                        iterator.remove()
                    }
                }
                scheduleUiUpdate()
                mainHandler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun scheduleUiUpdate() {
        // Логика обновления интерфейса
    }
}
