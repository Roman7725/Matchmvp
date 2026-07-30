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
 * Event configuration code.
 * Participants with the same EVENT_CODE will discover each other.
 */
private const val EVENT_CODE = "pilot-event-1"

class MainActivity : AppCompatActivity() {

    private lateinit var repository: MatchRepository
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var adapter: PeerAdapter

    private val scope = MainScope()
    private val discoveredPeers = mutableMapOf<String, NearbyPeer>()
    private val knownMatches = mutableSetOf<String>()
    
    private var myAnonymousId: String = UUID.randomUUID().toString().take(8)
    private var myNickname: String = ""
    private var myPhoneNumber: String = ""
    private var myBadgeEnabled: Boolean = false
    private var matchesListener: ListenerRegistration? = null

    private lateinit var joinScreen: LinearLayout
    private lateinit var roomScreen: LinearLayout

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleAndFirestore()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Permissions required")
                .setMessage("Bluetooth and location permissions are required to discover nearby participants.")
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
        val ageCheck = findViewById<CheckBox>(R.id.ageCheck)
        val badgeCheck = findViewById<CheckBox>(R.id.badgeCheck)
        val joinBtn = findViewById<Button>(R.id.joinBtn)
        val recyclerView = findViewById<RecyclerView>(R.id.peersRecyclerView)

        // Safe lookup for leaveBtn to prevent compile error if missing in layout XML
        val leaveBtnId = resources.getIdentifier("leaveBtn", "id", packageName)
        val leaveBtn: Button? = if (leaveBtnId != 0) findViewById(leaveBtnId) else null

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
            if (nickname.isEmpty() || phone.isEmpty() || !ageCheck.isChecked) {
                AlertDialog.Builder(this)
                    .setMessage("Please enter your nickname, phone number, and accept the terms.")
                    .setPositiveButton("OK", null)
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
                repository.registerParticipant(myNickname, myPhoneNumber, myAnonymousId)
                repository.setCommunityVisible(myBadgeEnabled)
                matchesListener = repository.listenForMatches { matchId, otherUid -> onMatchFound(matchId, otherUid) }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Error")
                        .setMessage("Failed to connect to backend service.\n\nError details: ${e.javaClass.simpleName}: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
        }

        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter: BluetoothAdapter? = btManager?.adapter
        if (btAdapter == null) {
            AlertDialog.Builder(this)
                .setMessage("Bluetooth adapter not found. Nearby discovery is unavailable.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        advertiser = BleAdvertiser(btAdapter)
        scanner = BleScanner(btAdapter) { peer -> onPeerDiscovered(peer) }

        advertiser.startAdvertising(myAnonymousId)
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
        adapter.submitList(emptyList())
    }

    private val peerNicknames = mutableMapOf<String, String>()
    private val peerBadges = mutableMapOf<String, Boolean>()
    private var uiUpdateScheduled = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onPeerDiscovered(peer: NearbyPeer) {
        discoveredPeers[peer.anonymousId] = peer

        if (!peerNicknames.containsKey(peer.anonymousId)) {
            scope.launch {
                val realUid = repository.resolveUidForAnonymousId(peer.anonymousId) ?: return@launch
                val fetchedNickname = repository.getParticipantNickname(realUid) ?: "User"
                peerNicknames[peer.anonymousId] = fetchedNickname

                if (myBadgeEnabled) {
                    val hasBadge = repository.hasCommunityBadge(realUid)
                    peerBadges[peer.anonymousId] = hasBadge
                }
                scheduleUiUpdate()
            }
        } else {
            scheduleUiUpdate()
        }
    }

    private fun scheduleUiUpdate() {
        if (uiUpdateScheduled) return
        uiUpdateScheduled = true
        mainHandler.postDelayed({
            uiUpdateScheduled = false
            val uiList = discoveredPeers.keys.map { anonymousId ->
                UiPeer(
                    uid = anonymousId,
                    avatarLabel = peerNicknames[anonymousId] ?: "Scanning...",
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
                .setTitle("It's a Match! 🎉")
                .setMessage("You both liked each other! Would you like to share your phone numbers?")
                .setPositiveButton("Share Number") { _, _ ->
                    scope.launch {
                        repository.revealPhoneTo(matchId, otherUid)
                        repository.listenForReveal(matchId, otherUid) { theirPhone ->
                            runOnUiThread {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Phone Number Shared")
                                    .setMessage("Their contact number: $theirPhone")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
                .setNegativeButton("Not Now", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleAndFirestore()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
