package com.matchmvp.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
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

        // ИНИЦИАЛИЗАЦИЯ КНОПОК И ЛОГИКИ
        setupLoginLogic()
        setupLanguageSwitch()

        startCleanupTask()
    }

    // ==========================================
    // 1. ИСПРАВЛЕНИЕ ЛОГИКИ КНОПКИ "ВОЙТИ"
    // ==========================================
    private fun setupLoginLogic() {
        // Замени R.id.btnLogin и R.id.etUsername / R.id.etPassword на ID из своего activity_main.xml
        val btnLogin = findViewById<Button?>(R.id.btnLogin)
        val etUsername = findViewById<EditText?>(R.id.etUsername)

        btnLogin?.setOnClickListener {
            val username = etUsername?.text?.toString()?.trim().orEmpty()

            if (username.isEmpty()) {
                Toast.makeText(this, "Введите имя или логин!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Успешный вход: $username", Toast.LENGTH_SHORT).show()
                
                // Переход на следующий экран (если есть вторая Activity, раскомментируй):
                // val intent = Intent(this, HomeActivity::class.java)
                // startActivity(intent)
                // finish()
            }
        }
    }

    // ==========================================
    // 2. ИСПРАВЛЕНИЕ СМЕНЫ ЯЗЫКА (RU / EN)
    // ==========================================
    private fun setupLanguageSwitch() {
        // Замени R.id.btnLangRu и R.id.btnLangEn на ID своих кнопок переключения языка
        val btnLangRu = findViewById<Button?>(R.id.btnLangRu)
        val btnLangEn = findViewById<Button?>(R.id.btnLangEn)

        btnLangRu?.setOnClickListener { setAppLanguage("ru") }
        btnLangEn?.setOnClickListener { setAppLanguage("en") }
    }

    private fun setAppLanguage(languageCode: String) {
        val currentLang = resources.configuration.locales.get(0).language
        if (currentLang == languageCode) return // Язык уже выбран

        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)

        // Обновляем конфигурацию приложения
        baseContext.resources.updateConfiguration(
            config,
            baseContext.resources.displayMetrics
        )

        // КРИТИЧЕСКИ ВАЖНО: перезапускаем активность для обновления текста UI
        recreate()
    }

    // ==========================================
    // 3. BLE И ФОНОВАЯ ЛОГИКА MATCHMVP
    // ==========================================
    private fun onPeerDiscovered(peer: NearbyPeer) {
        val parts = peer.anonymousId.split(":")
        val nickname = if (parts.size >= 2) parts[0] else "User"
        val anonId = if (parts.size >= 2) parts[1] else peer.anonymousId

        if (blockedUsers.contains(anonId)) return

        discoveredPeers[anonId] = peer
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
        // Логика обновления списка участников
    }
}
