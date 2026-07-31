package com.matchmvp.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await

class MatchRepository(private val eventCode: String) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val currentUid: String
        get() = auth.currentUser?.uid ?: error("User not authenticated")

    suspend fun signInAnonymously() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    suspend fun registerParticipant(nickname: String, phone: String, anonymousId: String) {
        val data = hashMapOf(
            "nickname" to nickname,
            "phone" to phone,
            "anonymousId" to anonymousId,
            "eventCode" to eventCode,
            "timestamp" to FieldValue.serverTimestamp()
        )
        db.collection("events").document(eventCode)
            .collection("participants").document(currentUid)
            .set(data).await()

        db.collection("events").document(eventCode)
            .collection("anonymous_map").document(anonymousId)
            .set(mapOf("uid" to currentUid)).await()
    }

    suspend fun getParticipantNickname(uid: String): String? {
        val doc = db.collection("events").document(eventCode)
            .collection("participants").document(uid).get().await()
        return doc.getString("nickname")
    }

    suspend fun resolveUidForAnonymousId(anonymousId: String): String? {
        val doc = db.collection("events").document(eventCode)
            .collection("anonymous_map").document(anonymousId).get().await()
        return doc.getString("uid")
    }

    suspend fun sendLike(targetUid: String) {
        val myUid = currentUid
        val likeData = hashMapOf(
            "from" to myUid,
            "to" to targetUid,
            "timestamp" to FieldValue.serverTimestamp()
        )
        db.collection("events").document(eventCode)
            .collection("likes")
            .document("${myUid}_$targetUid")
            .set(likeData).await()

        val reciprocalLike = db.collection("events").document(eventCode)
            .collection("likes")
            .document("${targetUid}_$myUid").get().await()

        if (reciprocalLike.exists()) {
            createMatch(myUid, targetUid)
        }
    }

    private suspend fun createMatch(uid1: String, uid2: String) {
        val matchId = if (uid1 < uid2) "${uid1}_$uid2" else "${uid2}_$uid1"
        val matchData = hashMapOf(
            "users" to listOf(uid1, uid2),
            "timestamp" to FieldValue.serverTimestamp()
        )
        db.collection("events").document(eventCode)
            .collection("matches")
            .document(matchId)
            .set(matchData).await()
    }

    fun listenForMatches(onMatch: (matchId: String, otherUid: String) -> Unit): ListenerRegistration {
        val myUid = currentUid
        return db.collection("events").document(eventCode)
            .collection("matches")
            .whereArrayContains("users", myUid)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                for (doc in snapshot.documents) {
                    val users = doc.get("users") as? List<*> ?: continue
                    val otherUid = users.firstOrNull { it != myUid } as? String ?: continue
                    onMatch(doc.id, otherUid)
                }
            }
    }

    suspend fun revealPhoneTo(matchId: String, otherUid: String) {
        val myUid = currentUid
        val myDoc = db.collection("events").document(eventCode)
            .collection("participants").document(myUid).get().await()
        val myPhone = myDoc.getString("phone") ?: "No phone"

        val updateData = hashMapOf<String, Any>(
            "phone_$myUid" to myPhone
        )

        db.collection("events").document(eventCode)
            .collection("matches").document(matchId)
            .update(updateData).await()
    }

    suspend fun listenForReveal(matchId: String, otherUid: String, onPhoneRevealed: (String) -> Unit) {
        db.collection("events").document(eventCode)
            .collection("matches").document(matchId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val phone = snapshot.getString("phone_$otherUid")
                if (phone != null) {
                    onPhoneRevealed(phone)
                }
            }
    }

    suspend fun setCommunityVisible(visible: Boolean) {
        db.collection("events").document(eventCode)
            .collection("participants").document(currentUid)
            .update("hasBadge", visible).await()
    }

    suspend fun hasCommunityBadge(uid: String): Boolean {
        val doc = db.collection("events").document(eventCode)
            .collection("participants").document(uid).get().await()
        return doc.getBoolean("hasBadge") == true
    }
}
