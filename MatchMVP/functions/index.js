/**
 * Cloud Function: проверка взаимных лайков и создание мэтча.
 *
 * Срабатывает при создании нового лайка. Смотрит (через Admin SDK,
 * который не подчиняется правилам безопасности клиента), есть ли
 * обратный лайк. Если есть — создаёт документ матча, который
 * увидят оба участника.
 *
 * Это единственное место в системе, где вообще возможно узнать
 * о взаимности лайка — клиент никогда не видит чужие лайки напрямую.
 */

const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp();

const db = admin.firestore();

exports.checkForMatch = functions.firestore
  .document('events/{eventCode}/likes/{likeId}')
  .onCreate(async (snap, context) => {
    const { eventCode } = context.params;
    const like = snap.data(); // { from, to, ts }

    const reverseLikeId = `${like.to}_${like.from}`;
    const reverseLikeRef = db
      .collection('events').doc(eventCode)
      .collection('likes').doc(reverseLikeId);

    const reverseLikeSnap = await reverseLikeRef.get();
    if (!reverseLikeSnap.exists) {
      return null; // лайк пока не взаимный, ничего не делаем
    }

    const pairKey = [like.from, like.to].sort().join('__');
    const matchRef = db
      .collection('events').doc(eventCode)
      .collection('matches').doc(pairKey);

    const existingMatch = await matchRef.get();
    if (existingMatch.exists) {
      return null; // мэтч уже был создан (например, обратный лайк
                    // тоже вызвал эту функцию почти одновременно)
    }

    await matchRef.set({
      participants: [like.from, like.to],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    return null;
  });
