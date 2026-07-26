package com.matchmvp.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await

/**
 * Вся "секретная" логика (лайки, мэтчи) идёт через Firestore.
 * Клиент физически не может прочитать чужие лайки — это гарантируют
 * правила безопасности (firestore.rules), а не только клиентский код.
 */
class MatchRepository(private val eventCode: String) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val myUid: String? get() = auth.currentUser?.uid

    suspend fun signInAnonymously(): String {
        val result = auth.signInAnonymously().await()
        return result.user!!.uid
    }

    suspend fun registerParticipant(avatarId: String, phoneNumber: String, anonymousId: String) {
        val uid = myUid ?: return
        db.collection("events").document(eventCode)
            .collection("participants").document(uid)
            .set(mapOf(
                "avatarId" to avatarId,
                "anonymousId" to anonymousId,
                "joinedAt" to System.currentTimeMillis(),
                "active" to true
            ))
            .await()
        // Телефон хранится ОТДЕЛЬНО от публичного профиля участника —
        // в свою собственную "приватную" запись, которую видит только сервер
        // и (после мэтча) сам владелец через revealPhoneTo().
        db.collection("events").document(eventCode)
            .collection("private").document(uid)
            .set(mapOf("phoneNumber" to phoneNumber))
            .await()
    }

    private val anonymousIdCache = mutableMapOf<String, String>() // anonymousId -> Firestore uid

    /**
     * КЛЮЧЕВОЙ ФИКС: Bluetooth транслирует только короткий анонимный ID,
     * который сам по себе НЕ является идентификатором пользователя в базе.
     * Прежде чем лайкнуть/матчить человека, найденного по Bluetooth, нужно
     * сначала узнать, какому реальному uid в Firestore соответствует
     * этот anonymousId — без этого шага лайки уходили "в никуда" и
     * мэтчи никогда бы не сработали.
     */
    suspend fun resolveUidForAnonymousId(anonymousId: String): String? {
        anonymousIdCache[anonymousId]?.let { return it }
        val snap = db.collection("events").document(eventCode)
            .collection("participants")
            .whereEqualTo("anonymousId", anonymousId)
            .limit(1)
            .get()
            .await()
        val uid = snap.documents.firstOrNull()?.id
        if (uid != null) anonymousIdCache[anonymousId] = uid
        return uid
    }

    suspend fun sendLike(targetUid: String) {
        val uid = myUid ?: return
        val likeId = "${uid}_$targetUid"
        db.collection("events").document(eventCode)
            .collection("likes").document(likeId)
            .set(mapOf("from" to uid, "to" to targetUid, "ts" to System.currentTimeMillis()))
            .await()
        // Обрати внимание: мы НЕ проверяем здесь взаимность на клиенте —
        // это делает исключительно серверная Cloud Function.
    }

    /** Слушает появление мэтчей, где участвую я. */
    fun listenForMatches(onMatch: (matchId: String, otherUid: String) -> Unit): ListenerRegistration {
        val uid = myUid!!
        return db.collection("events").document(eventCode)
            .collection("matches")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    val participants = change.document.get("participants") as? List<*> ?: return@forEach
                    val otherUid = participants.firstOrNull { it != uid } as? String ?: return@forEach
                    onMatch(change.document.id, otherUid)
                }
            }
    }

    /**
     * Раскрытие телефона — только сам владелец может инициировать раскрытие
     * СВОЕГО номера, читая его из своей приватной записи. Нельзя запросить
     * чужой номер напрямую — только явное согласие каждой стороны отдельно.
     */
    /**
     * Значок сообщества. Ты сам решаешь, включать ли его — по умолчанию
     * выключен. Если включён, ты сможешь увидеть значок у других людей
     * рядом, у которых он ТОЖЕ включён — и наоборот, они увидят твой,
     * только если у них тоже включено. Никакой односторонней видимости.
     */
    suspend fun setCommunityVisible(enabled: Boolean) {
        val uid = myUid ?: return
        db.collection("events").document(eventCode)
            .collection("community").document(uid)
            .set(mapOf("enabled" to enabled))
            .await()
    }

    /**
     * Проверяет, включён ли значок у другого человека. Сработает и
     * вернёт результат ТОЛЬКО если у тебя самого значок тоже включён —
     * это гарантируется правилами безопасности на сервере (firestore.rules),
     * а не только клиентским кодом.
     */
    suspend fun hasCommunityBadge(otherUid: String): Boolean {
        return try {
            val doc = db.collection("events").document(eventCode)
                .collection("community").document(otherUid)
                .get().await()
            doc.getBoolean("enabled") == true
        } catch (e: Exception) {
            false // либо у собеседника выключено, либо у нас самих — либо ошибка сети
        }
    }

    suspend fun revealPhoneTo(matchId: String, otherUid: String) {
        val uid = myUid ?: return
        val myPrivate = db.collection("events").document(eventCode)
            .collection("private").document(uid).get().await()
        val phoneNumber = myPrivate.getString("phoneNumber") ?: return

        db.collection("events").document(eventCode)
            .collection("reveals").document("${matchId}_$uid")
            .set(
                mapOf(
                    "uid" to uid,
                    "matchParticipants" to listOf(uid, otherUid),
                    "phoneNumber" to phoneNumber
                )
            ).await()
    }

    fun listenForReveal(matchId: String, otherUid: String, onRevealed: (String) -> Unit): ListenerRegistration {
        return db.collection("events").document(eventCode)
            .collection("reveals").document("${matchId}_$otherUid")
            .addSnapshotListener { snap, _ ->
                val phoneNumber = snap?.getString("phoneNumber") ?: return@addSnapshotListener
                onRevealed(phoneNumber)
            }
    }
}
