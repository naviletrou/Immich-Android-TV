package nl.giejay.android.tv.immich.search

import android.content.Context
import com.google.gson.Gson
import nl.giejay.android.tv.immich.api.model.FilterSearchRequest
import nl.giejay.android.tv.immich.api.model.SmartSearchRequest
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What the text of the search looks into, matching the search modes of the Immich web page. */
enum class SearchMode { SMART, FILE_NAME, DESCRIPTION, OCR }

/** The filters of the search view, the ones offered by the Immich server. */
data class SearchFilters(
    val mode: SearchMode = SearchMode.SMART,
    val text: String = "",
    val personId: String? = null,
    val personName: String? = null,
    val country: String? = null,
    val city: String? = null,
    val make: String? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** IMAGE, VIDEO or null for both. */
    val type: String? = null,
    val favoritesOnly: Boolean = false
) {
    val hasText: Boolean get() = text.isNotBlank()

    fun isEmpty(): Boolean = !hasText && personId == null && country == null && city == null && make == null &&
        yearFrom == null && yearTo == null && type == null && !favoritesOnly

    /** Smart search is only used with a text, the other filters go through the metadata search. */
    val usesSmartSearch: Boolean get() = mode == SearchMode.SMART && hasText

    private fun people(): List<UUID>? = personId?.let { listOf(UUID.fromString(it)) }

    private fun takenAfter(): String? = yearFrom?.let { startOfYear(it) }

    private fun takenBefore(): String? = yearTo?.let { startOfYear(it + 1) }

    fun toSmartRequest(page: Int, size: Int) = SmartSearchRequest(
        query = text.trim(),
        page = page,
        size = size,
        language = Locale.getDefault().language.takeIf { it.isNotBlank() },
        city = city,
        country = country,
        make = make,
        personIds = people(),
        takenAfter = takenAfter(),
        takenBefore = takenBefore(),
        type = type,
        isFavorite = if (favoritesOnly) true else null
    )

    fun toFilterRequest(page: Int, size: Int, order: String) = FilterSearchRequest(
        page = page,
        size = size,
        order = order,
        // a text in smart mode without machine learning never gets here, see usesSmartSearch
        originalFileName = text.trim().takeIf { hasText && mode == SearchMode.FILE_NAME },
        description = text.trim().takeIf { hasText && mode == SearchMode.DESCRIPTION },
        ocr = text.trim().takeIf { hasText && mode == SearchMode.OCR },
        city = city,
        country = country,
        make = make,
        personIds = people(),
        takenAfter = takenAfter(),
        takenBefore = takenBefore(),
        type = type,
        isFavorite = if (favoritesOnly) true else null
    )

    companion object {
        private const val PREFS = "immich_search"
        private const val KEY = "filters"

        private fun startOfYear(year: Int): String =
            LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        /** The last search is kept, so it is still there when the view is opened again. */
        fun load(context: Context): SearchFilters {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return SearchFilters()
            return try {
                Gson().fromJson(json, SearchFilters::class.java)?.sanitized() ?: SearchFilters()
            } catch (e: Exception) {
                Timber.e(e, "Could not read the last search")
                SearchFilters()
            }
        }

        fun save(context: Context, filters: SearchFilters) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, Gson().toJson(filters)).apply()
        }
    }

    /** Gson ignores Kotlin defaults: make sure nothing required is null after reading. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitized(): SearchFilters = copy(
        mode = if (mode == null) SearchMode.SMART else mode,
        text = if (text == null) "" else text
    )
}
