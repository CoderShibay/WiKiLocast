package com.wikifm.data

data class ArticleItem(
    val title: String,
    val extract: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
