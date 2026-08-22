package com.wikifm.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class LocalRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wikifm_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    private inline fun <reified T> getList(key: String): List<T> = try {
        gson.fromJson(prefs.getString(key, "[]"), object : TypeToken<List<T>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun <T> saveList(key: String, list: List<T>) =
        prefs.edit().putString(key, gson.toJson(list)).apply()

    // History
    fun addToHistory(item: ArticleItem) {
        val list = getList<ArticleItem>("history").filter { it.title != item.title }.toMutableList()
        list.add(0, item.copy(timestamp = System.currentTimeMillis()))
        saveList("history", list.take(50))
    }
    fun loadHistory(): List<ArticleItem> = getList("history")
    fun clearHistory() = prefs.edit().remove("history").apply()

    // Bookmarks
    fun toggleBookmark(item: ArticleItem): Boolean {
        val list = getList<ArticleItem>("bookmarks").toMutableList()
        val had = list.removeAll { it.title == item.title }
        if (!had) list.add(0, item.copy(timestamp = System.currentTimeMillis()))
        saveList("bookmarks", list)
        return !had
    }
    fun loadBookmarks(): List<ArticleItem> = getList("bookmarks")
    fun isBookmarked(title: String) = getList<ArticleItem>("bookmarks").any { it.title == title }
    fun removeBookmark(title: String) = saveList("bookmarks", getList<ArticleItem>("bookmarks").filter { it.title != title })

    // Voice
    fun saveVoiceName(name: String) = prefs.edit().putString("voice_name", name).apply()
    fun loadVoiceName(): String = prefs.getString("voice_name", "") ?: ""
}
