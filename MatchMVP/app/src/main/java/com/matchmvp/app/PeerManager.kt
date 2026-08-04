package com.matchmvp.app

import android.content.Context
import android.content.SharedPreferences

class HistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("match_history_2", Context.MODE_PRIVATE)

    fun saveMatch(name: String, contact: String?) {
        val historySet = getMatches().toMutableSet()
        val entry = if (!contact.isNullOrEmpty() && contact != "NONE") "$name — $contact" else name
        if (!historySet.contains(entry)) {
            historySet.add(entry)
            prefs.edit().putStringSet("matches", historySet).apply()
        }
    }

    fun getMatches(): Set<String> = prefs.getStringSet("matches", emptySet()) ?: emptySet()

    fun clearHistory() {
        prefs.edit().remove("matches").apply()
    }
}
