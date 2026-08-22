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

    // Fetch full article text via extracts API
    suspend fun getFullArticle(title: String): Result<WikiSummary> = runCatching {
        val response = api.getFullExtract(title.toWikiPath())
        val page = response.query.pages.values
            .firstOrNull { !it.extract.isNullOrBlank() }
            ?: throw Exception("No content for: $title")
        WikiSummary(title = page.title.ifBlank { title }, extract = page.extract.cleanWikiText())
    }

    // Random article: get title first, then fetch full text
    suspend fun getRandomFullArticle(): Result<WikiSummary> = runCatching {
        val randomTitle = api.getRandomSummary().title
        val response = api.getFullExtract(randomTitle.toWikiPath())
        val page = response.query.pages.values
            .firstOrNull { !it.extract.isNullOrBlank() }
            ?: throw Exception("No content")
        WikiSummary(title = page.title.ifBlank { randomTitle }, extract = page.extract.cleanWikiText())
    }

    // Search results (short snippets — summary endpoint is fine here)
    suspend fun searchArticles(query: String): List<SearchResult> = runCatching {
        api.search(query).query.search
    }.getOrDefault(emptyList())

    suspend fun getRelatedTitles(title: String): List<String> = runCatching {
        api.getRelated(title.toWikiPath()).pages.map { it.title }
    }.getOrDefault(emptyList())

    // Strip == Section Headers == and collapse blank lines
    private fun String.cleanWikiText(): String =
        replace(Regex("={2,}[^=\n]+={2,}"), "")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
