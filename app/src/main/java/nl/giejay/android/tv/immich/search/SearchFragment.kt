package nl.giejay.android.tv.immich.search

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.SearchResponse
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import java.time.LocalDate

/**
 * Search view: the first card opens the filters offered by the Immich server (smart search on the content,
 * file name, description, text in the photo, person, country, city, camera, years, type, favorites),
 * the results follow in the grid.
 */
class SearchFragment : nl.giejay.android.tv.immich.assets.GenericAssetFragment() {

    private var filters: SearchFilters = SearchFilters()
    /** Filters being edited in the dialog, applied with the Search button. */
    private var draft: SearchFilters = SearchFilters()
    private var total: Int? = null
    private var hasNextPage = false
    private var failed = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        filters = SearchFilters.load(requireContext())
        super.onCreate(savedInstanceState)
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Asset>> {
        val first = page == startPage
        val current = filters
        if (first) {
            total = null
            failed = false
        }
        if (current.isEmpty() || (!first && !hasNextPage)) {
            hasNextPage = false
            return Either.Right(if (first) listOf(searchAsset) else emptyList())
        }
        val result: Either<String, SearchResponse> = if (current.usesSmartSearch) {
            apiClient.smartSearch(current.toSmartRequest(page, pageCount))
        } else {
            val order = if (currentSort == PhotosOrder.OLDEST_NEWEST) "asc" else "desc"
            apiClient.filterSearch(current.toFilterRequest(page, pageCount, order))
        }
        return result.fold(
            { error ->
                hasNextPage = false
                if (first) failed = true
                showError(error)
                // the search card must stay, so the filters can be changed
                Either.Right(if (first) listOf(searchAsset) else emptyList())
            },
            { response ->
                hasNextPage = response.assets.nextPage != null
                if (first) {
                    total = response.assets.total ?: if (!hasNextPage) response.assets.count else null
                    Either.Right(listOf(searchAsset) + response.assets.items)
                } else {
                    Either.Right(response.assets.items)
                }
            }
        )
    }

    override fun allPagesLoaded(items: List<Asset>): Boolean = !hasNextPage

    override fun filterItems(items: List<Asset>): List<Asset> {
        // the search has its own photo / video filter, the general one does not apply here
        val (card, found) = items.partition { it.id == SEARCH_CARD_ID }
        return card + found.filter(excludeByTag()).filterNot { excludedAssetIds.contains(it.id) }
    }

    override fun sortItems(items: List<Asset>): List<Asset> {
        // the server already sorted them: by date, or by relevance for the smart search
        val (card, found) = items.partition { it.id == SEARCH_CARD_ID }
        return card + found
    }

    override fun sliderAssets(): List<Asset> = assets.filter { it.id != SEARCH_CARD_ID }

    private fun applyFilters(newFilters: SearchFilters) {
        filters = newFilters
        SearchFilters.save(requireContext(), newFilters)
        clearState()
        allPagesLoaded = false
        hasNextPage = false
        fetchInitialItems()
    }

    private suspend fun showError(error: String) = withContext(Dispatchers.Main) {
        if (!isAdded) return@withContext
        val message = if (filters.usesSmartSearch) getString(R.string.search_smart_failed, error)
        else getString(R.string.search_failed, error)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------
    // Cards
    // ---------------------------------------------------------------------------------------

    override fun createCard(a: Asset): Card {
        if (a.id != SEARCH_CARD_ID) return super.createCard(a)
        val summary = summary(filters)
        val description = when {
            filters.isEmpty() -> getString(R.string.search_choose_filters)
            failed -> getString(R.string.search_error_short)
            total != null -> if (total == 0) getString(R.string.search_no_result)
            else resources.getQuantityString(R.plurals.search_results, total!!, total!!).replace(",", " ")
            else -> getString(R.string.search_by_relevance)
        }
        return Card(
            if (summary.isEmpty()) getString(R.string.search_card) else summary,
            description,
            SEARCH_CARD_ID,
            SEARCH_ICON,
            null,
            false
        )
    }

    override fun getBackgroundPicture(it: Asset): String? =
        if (it.id == SEARCH_CARD_ID) null else super.getBackgroundPicture(it)

    override fun onItemClicked(card: Card) {
        if (card.id == SEARCH_CARD_ID) {
            draft = filters
            showFiltersDialog()
        } else {
            super.onItemClicked(card)
        }
    }

    /** Short text of the active filters, shown on the search card. */
    private fun summary(f: SearchFilters): String {
        val parts = mutableListOf<String>()
        if (f.hasText) parts += "« ${f.text.trim()} »"
        f.personName?.let { parts += it }
        (f.city ?: f.country)?.let { parts += it }
        f.make?.let { parts += it }
        years(f)?.let { parts += it }
        when (f.type) {
            "IMAGE" -> parts += getString(R.string.search_type_photos)
            "VIDEO" -> parts += getString(R.string.search_type_videos)
        }
        if (f.favoritesOnly) parts += "★"
        return parts.joinToString(" · ")
    }

    private fun years(f: SearchFilters): String? = when {
        f.yearFrom != null && f.yearTo != null ->
            if (f.yearFrom == f.yearTo) "${f.yearFrom}" else "${f.yearFrom} – ${f.yearTo}"
        f.yearFrom != null -> getString(R.string.search_since, f.yearFrom)
        f.yearTo != null -> getString(R.string.search_until, f.yearTo)
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Filters dialog (a list that is easy to use with the remote control)
    // ---------------------------------------------------------------------------------------

    private fun modeTitle(mode: SearchMode): String = getString(
        when (mode) {
            SearchMode.SMART -> R.string.search_mode_smart
            SearchMode.FILE_NAME -> R.string.search_mode_file_name
            SearchMode.DESCRIPTION -> R.string.search_mode_description
            SearchMode.OCR -> R.string.search_mode_ocr
        }
    )

    private fun showFiltersDialog() {
        if (!isAdded) return
        val context = requireContext()
        val none = getString(R.string.search_any)
        val rows = listOf(
            getString(R.string.search_row_mode, modeTitle(draft.mode)) to { chooseMode() },
            getString(R.string.search_row_text, if (draft.hasText) draft.text else none) to { editText() },
            getString(R.string.search_row_person, draft.personName ?: none) to { choosePerson() },
            getString(R.string.search_row_country, draft.country ?: none) to { chooseCountry() },
            getString(R.string.search_row_city, draft.city ?: none) to { chooseCity() },
            getString(R.string.search_row_camera, draft.make ?: none) to { chooseCamera() },
            getString(R.string.search_row_year_from, draft.yearFrom?.toString() ?: none) to { chooseYear(true) },
            getString(R.string.search_row_year_to, draft.yearTo?.toString() ?: none) to { chooseYear(false) },
            getString(R.string.search_row_type, typeTitle(draft.type)) to { chooseType() },
            getString(R.string.search_row_favorites,
                getString(if (draft.favoritesOnly) R.string.search_yes else R.string.search_no)) to {
                draft = draft.copy(favoritesOnly = !draft.favoritesOnly)
                showFiltersDialog()
            }
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.search_filters_title)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setPositiveButton(R.string.search_button) { _, _ -> applyFilters(draft) }
            .setNeutralButton(R.string.search_clear) { _, _ -> applyFilters(SearchFilters(mode = draft.mode)) }
            .setNegativeButton(R.string.people_search_cancel, null)
            .show()
    }

    private fun typeTitle(type: String?): String = getString(
        when (type) {
            "IMAGE" -> R.string.search_type_photos
            "VIDEO" -> R.string.search_type_videos
            else -> R.string.search_type_all
        }
    )

    /** A list to pick one value; the first line removes the filter. Returns to the filters afterwards. */
    private fun choose(title: Int, values: List<String>, current: String?, onChosen: (Int?) -> Unit) {
        if (!isAdded) return
        val items = listOf(getString(R.string.search_any)) + values
        val selected = current?.let { values.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected.coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                onChosen(if (which == 0) null else which - 1)
                showFiltersDialog()
            }
            .setOnCancelListener { showFiltersDialog() }
            .show()
    }

    private fun chooseMode() {
        val modes = SearchMode.entries
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.search_mode_title)
            .setSingleChoiceItems(modes.map { modeTitle(it) }.toTypedArray(), modes.indexOf(draft.mode)) { dialog, which ->
                dialog.dismiss()
                draft = draft.copy(mode = modes[which])
                // a text is needed for these modes
                if (draft.hasText) showFiltersDialog() else editText()
            }
            .setOnCancelListener { showFiltersDialog() }
            .show()
    }

    private fun editText() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText(draft.text)
            setSelection(text.length)
            hint = getString(
                when (draft.mode) {
                    SearchMode.SMART -> R.string.search_hint_smart
                    SearchMode.FILE_NAME -> R.string.search_hint_file_name
                    SearchMode.DESCRIPTION -> R.string.search_hint_description
                    SearchMode.OCR -> R.string.search_hint_ocr
                }
            )
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        var done = false
        fun finish(apply: Boolean) {
            if (done) return
            done = true
            if (apply) {
                // Enter on the keyboard launches the search straight away
                draft = draft.copy(text = input.text.toString().trim())
                applyFilters(draft)
            } else {
                showFiltersDialog()
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(modeTitle(draft.mode))
            .setView(container)
            .setPositiveButton(R.string.search_button) { _, _ -> finish(true) }
            .setNeutralButton(R.string.search_filters_button) { _, _ ->
                draft = draft.copy(text = input.text.toString().trim())
                finish(false)
            }
            .setNegativeButton(R.string.people_search_cancel) { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss()
                finish(true)
                true
            } else false
        }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    /** Fetches values from the server, then shows them in a list. */
    private fun <T> loadThenChoose(load: suspend () -> Either<String, List<T>>, show: (List<T>) -> Unit) {
        val context: Context = requireContext()
        Toast.makeText(context, R.string.search_loading, Toast.LENGTH_SHORT).show()
        ioScope.launch {
            val result = load()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.fold(
                    { error ->
                        Toast.makeText(context, getString(R.string.search_failed, error), Toast.LENGTH_LONG).show()
                        showFiltersDialog()
                    },
                    { values -> show(values) }
                )
            }
        }
    }

    private fun choosePerson() {
        loadThenChoose({ apiClient.listPeople() }) { people ->
            val sorted = people.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            choose(R.string.search_person_title, sorted.map { it.name ?: "" }, draft.personName) { index ->
                val person = index?.let { sorted[it] }
                draft = draft.copy(personId = person?.id?.toString(), personName = person?.name)
            }
        }
    }

    private fun chooseCountry() {
        loadThenChoose({ apiClient.searchSuggestions("country") }) { countries ->
            choose(R.string.search_country_title, countries, draft.country) { index ->
                val country = index?.let { countries[it] }
                // a city of another country would give no result
                draft = draft.copy(country = country, city = if (country != draft.country) null else draft.city)
            }
        }
    }

    private fun chooseCity() {
        loadThenChoose({ apiClient.searchSuggestions("city", country = draft.country) }) { cities ->
            choose(R.string.search_city_title, cities, draft.city) { index ->
                draft = draft.copy(city = index?.let { cities[it] })
            }
        }
    }

    private fun chooseCamera() {
        loadThenChoose({ apiClient.searchSuggestions("camera-make") }) { makes ->
            choose(R.string.search_camera_title, makes, draft.make) { index ->
                draft = draft.copy(make = index?.let { makes[it] })
            }
        }
    }

    private fun chooseYear(from: Boolean) {
        val years = (LocalDate.now().year downTo FIRST_YEAR).map { it.toString() }
        val current = if (from) draft.yearFrom else draft.yearTo
        choose(if (from) R.string.search_year_from_title else R.string.search_year_to_title, years,
            current?.toString()) { index ->
            val year = index?.let { years[it].toInt() }
            draft = if (from) {
                draft.copy(yearFrom = year, yearTo = draft.yearTo?.let { to -> if (year != null && to < year) year else to })
            } else {
                draft.copy(yearTo = year, yearFrom = draft.yearFrom?.let { f -> if (year != null && f > year) year else f })
            }
        }
    }

    private fun chooseType() {
        val types = listOf("IMAGE", "VIDEO")
        choose(R.string.search_type_title, types.map { typeTitle(it) },
            draft.type?.let { typeTitle(it) }) { index ->
            draft = draft.copy(type = index?.let { types[it] })
        }
    }

    companion object {
        private const val SEARCH_CARD_ID = "search"
        private const val SEARCH_ICON = "people_search"
        private const val FIRST_YEAR = 1900

        /** Placeholder item for the search card at the start of the grid. */
        private val searchAsset = Asset(
            id = SEARCH_CARD_ID,
            type = "SEARCH",
            deviceAssetId = null,
            exifInfo = null,
            fileModifiedAt = null,
            albumName = null,
            people = null,
            tags = null,
            originalPath = null,
            originalFileName = null
        )
    }
}
