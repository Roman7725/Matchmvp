package com.matchmvp.app

import android.Manifest
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private val blockedUsers = HashSet<String>()
    private val discoveredPeers = ConcurrentHashMap<String, NearbyPeer>()
    private val peerNicknames = ConcurrentHashMap<String, String>()
    private val lastSeenTimes = ConcurrentHashMap<String, Long>()
    private val peerRssiMap = ConcurrentHashMap<String, Int>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val PERMISSION_REQUEST_CODE = 101

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

        // 1. КНОПКА "ВОЙТИ В ЭФИР" + ЗАПРОС РАЗРЕШЕНИЙ
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

            // Проверяем и запрашиваем разрешения Bluetooth и Геолокации
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
            } else {
                enterRoom(joinScreen, roomScreen)
            }
        }

        // Кнопка "Выйти / Leave"
        leaveBtn?.setOnClickListener {
            roomScreen?.visibility = View.GONE
            joinScreen?.visibility = View.VISIBLE
        }

        // 2. ПЕРЕКЛЮЧЕНИЕ ЯЗЫКА (RU <-> EN)
        langBtn?.setOnClickListener {
            val currentLocales = AppCompatDelegate.getApplicationLocales()
            val currentLang = if (!currentLocales.isEmpty) currentLocales.get(0)?.language else "ru"

            val newLang = if (currentLang == "ru") "en" else "ru"
            val appLocales = LocaleListCompat.forLanguageTags(newLang)
            
            // Сохраняет выбранный язык и автоматические пересоздает UI
            AppCompatDelegate.setApplicationLocales(appLocales)
        }
    }

    private fun enterRoom(joinScreen: LinearLayout?, roomScreen: LinearLayout?) {
        joinScreen?.visibility = View.GONE
        roomScreen?.visibility = View.VISIBLE
        Toast.makeText(this, "Вы вошли в эфир!", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // ПРОВЕРКА И ЗАПРОС РАЗРЕШЕНИЙ (BLE / GPS)
    // ==========================================
    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
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
                enterRoom(joinScreen, roomScreen)
            } else {
                Toast.makeText(this, "Для поиска устройств необходим доступ к Bluetooth и GPS!", Toast.LENGTH_LONG).show()
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
