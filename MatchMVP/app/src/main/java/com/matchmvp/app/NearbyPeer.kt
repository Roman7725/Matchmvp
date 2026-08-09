package com.matchmvp.app

data class NearbyPeer(
    val uid: String,
    val nickname: String,
    val status: String,
    val likedTargetUid: String,
    val avatarId: Int,
    val contactInfo: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

data class UiPeer(
    val uid: String,
    val avatarLabel: String,
    val liked: Boolean,
    val hasBadge: Boolean
)
