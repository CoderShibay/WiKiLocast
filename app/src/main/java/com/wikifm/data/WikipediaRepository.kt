package com.wikifm.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class WikipediaRepository {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "WikiFM/1.0 (Android app; open source)")
                .build()
            chain.proceed(req)
        }
        .build()

    private val api: WikipediaApi = Retrofit.Builder()
        .baseUrl("https://en.wikipedia.org/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WikipediaApi::class.java)

    private fun String.toWikiPath() = trim().replace(" ", "_")

    suspend fun searchArticles(query: String): List<SearchResult> = runCatching {
        api.search(query).query.search
    }.getOrDefault(emptyList())

    suspend fun getSummary(title: String): Result<WikiSummary> = runCatching {
        api.getSummary(title.toWikiPath())
    }

    suspend fun getRandomSummary(): Result<WikiSummary> = runCatching {
        api.getRandomSummary()
    }

    suspend fun getRelatedTitles(title: String): List<String> = runCatching {
        api.getRelated(title.toWikiPath()).pages.map { it.title }
    }.getOrDefault(emptyList())
}
