package com.wikifm.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class WikiSummary(
    val title: String,
    val extract: String = ""
)

data class RelatedResponse(val pages: List<RelatedPage>)
data class RelatedPage(val title: String)

data class SearchResponse(val query: SearchQuery)
data class SearchQuery(val search: List<SearchResult>)
data class SearchResult(
    val title: String,
    val snippet: String = ""
) {
    val cleanSnippet: String get() = snippet.replace(Regex("<[^>]+>"), "").trim()
}

// Full article extract via action API
data class ExtractResponse(val query: ExtractQuery)
data class ExtractQuery(val pages: Map<String, ExtractPage>)
data class ExtractPage(val title: String = "", val extract: String = "")

interface WikipediaApi {
    // Short summary — used for search result cards only
    @GET("api/rest_v1/page/summary/{title}")
    suspend fun getSummary(@Path("title", encoded = true) title: String): WikiSummary

    // Random article title (we then fetch full text separately)
    @GET("api/rest_v1/page/random/summary")
    suspend fun getRandomSummary(): WikiSummary

    // Full plain-text article — explaintext strips wiki markup
    @GET("w/api.php?action=query&prop=extracts&format=json&explaintext=true&redirects=true")
    suspend fun getFullExtract(@Query("titles") title: String): ExtractResponse

    @GET("api/rest_v1/page/related/{title}")
    suspend fun getRelated(@Path("title", encoded = true) title: String): RelatedResponse

    @GET("w/api.php?action=query&list=search&format=json&srlimit=8&srnamespace=0&srprop=snippet")
    suspend fun search(@Query("srsearch") query: String): SearchResponse
}
