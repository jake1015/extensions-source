package eu.kanade.tachiyomi.multisrc.keyoapp

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

abstract class Keyoapp(
    override val name: String,
    override val baseUrl: String,
    final override val lang: String,
) : ParsedHttpSource() {
    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val json: Json by injectLazy()

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaSelector(): String = "div.flex-col div.grid > div.group.border"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        thumbnail_url = element.getImageUrl("*[style*=background-image]")
        element.selectFirst("a[href]")!!.run {
            title = attr("title")
            setUrlWithoutDomain(attr("abs:href"))
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        runCatching { fetchGenres() }
        return super.popularMangaParse(response)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/", headers)

    override fun latestUpdatesSelector(): String = "div.grid > div.group"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        runCatching { fetchGenres() }
        return super.latestUpdatesParse(response)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            addPathSegment("")
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }
            filters.firstOrNull { it is GenreList }?.also {
                val filter = it as GenreList
                filter.state
                    .filter { it.state }
                    .forEach { genre ->
                        addQueryParameter("genre", genre.id)
                    }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = "#searched_series_page > button"

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage {
        runCatching { fetchGenres() }
        val document = response.asJsoup()

        val query = response.request.url.queryParameter("q") ?: ""
        val genres = response.request.url.queryParameterValues("genre")

        val mangaList = document.select(searchMangaSelector())
            .toTypedArray()
            .filter { it.attr("title").contains(query, true) }
            .filter { entry ->
                val entryGenres = json.decodeFromString<List<String>>(entry.attr("tags"))
                genres.all { genre -> entryGenres.any { it.equals(genre, true) } }
            }
            .map(::searchMangaFromElement)

        return MangasPage(mangaList, false)
    }

    // Filters

    /**
     * Automatically fetched genres from the source to be used in the filters.
     */
    private var genresList: List<Genre> = emptyList()

    /**
     * Inner variable to control the genre fetching failed state.
     */
    private var fetchGenresFailed: Boolean = false

    /**
     * Inner variable to control how much tries the genres request was called.
     */
    private var fetchGenresAttempts: Int = 0

    class Genre(name: String, val id: String = name) : Filter.CheckBox(name)

    protected class GenreList(title: String, genres: List<Genre>) : Filter.Group<Genre>(title, genres)

    override fun getFilterList(): FilterList {
        return if (genresList.isNotEmpty()) {
            FilterList(
                GenreList("Genres", genresList),
            )
        } else {
            FilterList(
                Filter.Header("Press 'Reset' to attempt to show the genres"),
            )
        }
    }

    /**
     * Fetch the genres from the source to be used in the filters.
     */
    protected open fun fetchGenres() {
        if (fetchGenresAttempts <= 3 && (genresList.isEmpty() || fetchGenresFailed)) {
            val genres = runCatching {
                client.newCall(genresRequest()).execute()
                    .use { parseGenres(it.asJsoup()) }
            }

            fetchGenresFailed = genres.isFailure
            genresList = genres.getOrNull().orEmpty()
            fetchGenresAttempts++
        }
    }

    private fun genresRequest(): Request = GET("$baseUrl/series/", headers)

    /**
     * Get the genres from the search page document.
     *
     * @param document The search page document
     */
    protected open fun parseGenres(document: Document): List<Genre> {
        return document.select("#series_tags_page > button")
            .map { btn ->
                Genre(btn.text(), btn.attr("tag"))
            }
    }

    // Details

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("div.grid > h1")!!.text()
        thumbnail_url = document.getImageUrl("div[class*=photoURL]")
        description = document.selectFirst("div.grid > div.overflow-hidden > p")?.text()
        status = document.selectFirst("div[alt=Status]").parseStatus()
        author = document.selectFirst("div[alt=Author]")?.text()
        artist = document.selectFirst("div[alt=Artist]")?.text()
        genre = document.select("div.grid:has(>h1) > div > a").joinToString { it.text() }
    }

    private fun Element?.parseStatus(): Int = when (this?.text()?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "dropped" -> SManga.CANCELLED
        "paused" -> SManga.ON_HIATUS
        "completed" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapter list

    override fun chapterListSelector(): String = "#chapters > a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        name = element.selectFirst(".text-sm")!!.text()
        element.selectFirst(".text-xs")?.run {
            date_upload = text().trim().parseDate()
        }
    }

    // Image list

    override fun pageListParse(document: Document): List<Page> {
        return document.select("#pages > img").map {
            val index = it.attr("count").toInt()
            Page(index, document.location(), it.imgAttr("150"))
        }
    }

    override fun imageUrlParse(document: Document) = ""

    // Utilities

    // From mangathemesia
    private fun Element.imgAttr(width: String): String {
        val url = when {
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-src") -> attr("abs:data-src")
            else -> attr("abs:src")
        }
        return url.toHttpUrl()
            .newBuilder()
            .addQueryParameter("w", width)
            .build()
            .toString()
    }

    private fun Element.getImageUrl(selector: String): String? {
        return this.selectFirst(selector)?.let {
            it.attr("style")
                .substringAfter(":url(", "")
                .substringBefore(")", "")
                .takeIf { it.isNotEmpty() }
                ?.toHttpUrlOrNull()?.let {
                    it.newBuilder()
                        .setQueryParameter("w", "480")
                        .build()
                        .toString()
                }
        }
    }

    private fun String.parseDate(): Long {
        return runCatching { DATE_FORMATTER.parse(this)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
        }
    }
}
