package nl.giejay.android.tv.immich.search

import android.content.Context
import com.google.gson.Gson
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.MetadataSearchV3Request
import nl.giejay.android.tv.immich.api.model.SmartSearchV3Request
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the text of the search looks into, matching the search modes of the Immich web page. */
enum class SearchMode { SMART, FILE_NAME, DESCRIPTION, OCR }

/** The filters of a search, the ones offered by the Immich server. */
data class SearchFilters(
    val mode: SearchMode? = SearchMode.SMART,
    val text: String? = "",
    val personId: String? = null,
    val personName: String? = null,
    val country: String? = null,
    val city: String? = null,
    val make: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** IMAGE, VIDEO or null for both. */
    val type: String? = null,
    val favoritesOnly: Boolean = false,
    /** Full path of the folder the search is limited to (with its sub folders), null for everything. */
    val folderPath: String? = null,
    val folderName: String? = null
) {
    val searchMode: SearchMode get() = mode ?: SearchMode.SMART
    val query: String get() = text?.trim() ?: ""
    val hasText: Boolean get() = query.isNotEmpty()

    /** True when nothing at all restricts the search. */
    fun isEmpty(): Boolean = !hasText && personId == null && country == null && city == null && make == null &&
        yearFrom == null && yearTo == null && type == null && !favoritesOnly && folderPath == null

    /** Smart search is only used with a text, the other filters go through the metadata search. */
    val usesSmartSearch: Boolean get() = searchMode == SearchMode.SMART && hasText

    /** The filter object of the Immich v3 search, shared by the smart and the metadata search. */
    private fun filter(): Map<String, Any> {
        val f = mutableMapOf<String, Any>()
        // like the timeline: no archived or hidden assets (the hidden ones are the videos of live photos)
        f["visibility"] = mapOf("eq" to "timeline")
        folderPath?.let { path -> f["originalPath"] = mapOf("startsWith" to if (path.endsWith("/")) path else "$path/") }
        if (hasText) {
            when (searchMode) {
                SearchMode.FILE_NAME -> f["originalFileName"] = mapOf("like" to query)
                SearchMode.DESCRIPTION -> f["description"] = mapOf("like" to query)
                SearchMode.OCR -> f["ocr"] = mapOf("matches" to query)
                SearchMode.SMART -> Unit // the text is the query of the smart search
            }
        }
        personId?.let { f["personIds"] = mapOf("all" to listOf(it)) }
        country?.let { f["country"] = mapOf("eq" to it) }
        city?.let { f["city"] = mapOf("eq" to it) }
        make?.let { f["make"] = mapOf("eq" to it) }
        if (yearFrom != null || yearTo != null) {
            val dates = mutableMapOf<String, String>()
            yearFrom?.let { dates["gte"] = startOfYear(it) }
            yearTo?.let { dates["lt"] = startOfYear(it + 1) }
            f["takenAt"] = dates
        }
        type?.let { f["type"] = mapOf("eq" to it) }
        if (favoritesOnly) f["isFavorite"] = mapOf("eq" to true)
        return f
    }

    fun toSmartRequest(size: Int) = SmartSearchV3Request(
        query = query,
        size = size,
        language = Locale.getDefault().language.takeIf { it.isNotBlank() },
        filter = filter()
    )

    fun toMetadataRequest(size: Int, cursor: String?, newestFirst: Boolean) = MetadataSearchV3Request(
        size = size,
        cursor = cursor,
        orderBy = mapOf("field" to "fileCreatedAt", "direction" to if (newestFirst) "desc" else "asc"),
        filter = filter()
    )

    /** Short text of the active filters, for the cards and the titles. Empty when there is no filter. */
    fun summary(context: Context): String {
        val parts = mutableListOf<String>()
        if (hasText) parts += "« $query »"
        personName?.let { parts += it }
        (city ?: country)?.let { parts += it }
        make?.let { parts += it }
        when {
            yearFrom != null && yearTo != null ->
                parts += if (yearFrom == yearTo) "$yearFrom" else "$yearFrom – $yearTo"
            yearFrom != null -> parts += context.getString(R.string.search_since, yearFrom)
            yearTo != null -> parts += context.getString(R.string.search_until, yearTo)
        }
        when (type) {
            "IMAGE" -> parts += context.getString(R.string.search_type_photos)
            "VIDEO" -> parts += context.getString(R.string.search_type_videos)
        }
        if (favoritesOnly) parts += "★"
        folderName?.let { parts += context.getString(R.string.search_in_folder, it) }
        return parts.joinToString(" · ")
    }

    fun toJson(): String = Gson().toJson(this)

    companion object {
        private const val PREFS = "immich_search"
        private const val KEY = "filters"

        private fun startOfYear(year: Int): String =
            LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun fromJson(json: String?): SearchFilters {
            if (json.isNullOrBlank()) return SearchFilters()
            return try {
                Gson().fromJson(json, SearchFilters::class.java) ?: SearchFilters()
            } catch (e: Exception) {
                Timber.e(e, "Could not read the search")
                SearchFilters()
            }
        }

        /** The last search, offered again in the search view. */
        fun loadLast(context: Context): SearchFilters =
            fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun saveLast(context: Context, filters: SearchFilters) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, filters.toJson()).apply()
        }
    }
}
