package nl.giejay.android.tv.immich.search

import android.app.AlertDialog
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import timber.log.Timber
import java.time.LocalDate

/**
 * The search filters, as a list that is easy to use with the remote control: each line opens the choice of
 * one filter, "Search" launches the search with [onSearch].
 *
 * @param folder full path and name of the folder the search can be limited to, null outside the folders view
 */
class SearchDialog(
    private val fragment: Fragment,
    private val apiClient: ApiClient,
    private val scope: CoroutineScope,
    start: SearchFilters,
    private val folder: Pair<String, String>?,
    private val onSearch: (SearchFilters) -> Unit
) {
    private var draft: SearchFilters = start

    private val alive: Boolean get() = fragment.isAdded && fragment.activity?.isFinishing == false

    private fun str(id: Int, vararg args: Any): String = fragment.getString(id, *args)

    fun show() {
        if (!alive) return
        try {
            showFilters()
        } catch (e: Exception) {
            // a search must never take the whole app down
            Timber.e(e, "Could not show the search filters")
        }
    }

    private fun modeTitle(mode: SearchMode): String = str(
        when (mode) {
            SearchMode.SMART -> R.string.search_mode_smart
            SearchMode.FILE_NAME -> R.string.search_mode_file_name
            SearchMode.DESCRIPTION -> R.string.search_mode_description
            SearchMode.OCR -> R.string.search_mode_ocr
        }
    )

    private fun typeTitle(type: String?): String = str(
        when (type) {
            "IMAGE" -> R.string.search_type_photos
            "VIDEO" -> R.string.search_type_videos
            else -> R.string.search_type_all
        }
    )

    private fun showFilters() {
        if (!alive) return
        val none = str(R.string.search_any)
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows += str(R.string.search_row_mode, modeTitle(draft.searchMode)) to { chooseMode() }
        rows += str(R.string.search_row_text, if (draft.hasText) draft.query else none) to { editText() }
        val folderShown = folder ?: draft.folderPath?.let { it to (draft.folderName ?: it) }
        if (folderShown != null) {
            rows += str(R.string.search_row_folder,
                if (draft.folderPath != null) (draft.folderName ?: folderShown.second) else str(R.string.search_all_folders)) to {
                draft = if (draft.folderPath != null) draft.copy(folderPath = null, folderName = null)
                else draft.copy(folderPath = folderShown.first, folderName = folderShown.second)
                showFilters()
            }
        }
        rows += str(R.string.search_row_person, draft.personName ?: none) to { choosePerson() }
        rows += str(R.string.search_row_country, draft.country ?: none) to { chooseCountry() }
        rows += str(R.string.search_row_city, draft.city ?: none) to { chooseCity() }
        rows += str(R.string.search_row_camera, draft.make ?: none) to { chooseCamera() }
        rows += str(R.string.search_row_year_from, draft.yearFrom?.toString() ?: none) to { chooseYear(true) }
        rows += str(R.string.search_row_year_to, draft.yearTo?.toString() ?: none) to { chooseYear(false) }
        rows += str(R.string.search_row_type, typeTitle(draft.type)) to { chooseType() }
        rows += str(R.string.search_row_favorites, str(if (draft.favoritesOnly) R.string.search_yes else R.string.search_no)) to {
            draft = draft.copy(favoritesOnly = !draft.favoritesOnly)
            showFilters()
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.search_filters_title)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> safely { rows[which].second() } }
            .setPositiveButton(R.string.search_button) { _, _ -> launch() }
            .setNeutralButton(R.string.search_clear) { _, _ ->
                draft = SearchFilters(mode = draft.searchMode,
                    folderPath = folder?.first, folderName = folder?.second)
                showFilters()
            }
            .setNegativeButton(R.string.people_search_cancel, null)
            .show()
    }

    private fun launch() {
        if (!alive) return
        if (draft.isEmpty()) {
            Toast.makeText(fragment.requireContext(), R.string.search_nothing_chosen, Toast.LENGTH_LONG).show()
            showFilters()
            return
        }
        safely { onSearch(draft) }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            if (alive) Toast.makeText(fragment.requireContext(), str(R.string.search_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /** A list to pick one value; the first line removes the filter. Returns to the filters afterwards. */
    private fun choose(title: Int, values: List<String>, current: String?, onChosen: (Int?) -> Unit) {
        if (!alive) return
        val items = listOf(str(R.string.search_any)) + values
        val selected = current?.let { values.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected.coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                safely { onChosen(if (which == 0) null else which - 1) }
                showFilters()
            }
            .setOnCancelListener { showFilters() }
            .show()
    }

    private fun chooseMode() {
        val modes = SearchMode.entries
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.search_mode_title)
            .setSingleChoiceItems(modes.map { modeTitle(it) }.toTypedArray(), modes.indexOf(draft.searchMode)) { dialog, which ->
                dialog.dismiss()
                draft = draft.copy(mode = modes[which])
                // these modes need a text
                if (draft.hasText) showFilters() else editText()
            }
            .setOnCancelListener { showFilters() }
            .show()
    }

    private fun editText() {
        if (!alive) return
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            setText(draft.query)
            setSelection(text.length)
            hint = str(
                when (draft.searchMode) {
                    SearchMode.SMART -> R.string.search_hint_smart
                    SearchMode.FILE_NAME -> R.string.search_hint_file_name
                    SearchMode.DESCRIPTION -> R.string.search_hint_description
                    SearchMode.OCR -> R.string.search_hint_ocr
                }
            )
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val padding = (24 * fragment.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        var done = false
        fun finish(search: Boolean) {
            if (done) return
            done = true
            draft = draft.copy(text = input.text.toString().trim())
            // Enter on the keyboard launches the search straight away
            if (search) launch() else showFilters()
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(modeTitle(draft.searchMode))
            .setView(container)
            .setPositiveButton(R.string.search_button) { _, _ -> finish(true) }
            .setNegativeButton(R.string.search_filters_button) { _, _ -> finish(false) }
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
        if (!alive) return
        Toast.makeText(fragment.requireContext(), R.string.search_loading, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = try {
                load()
            } catch (e: Exception) {
                Either.Left(e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) {
                if (!alive) return@withContext
                result.fold(
                    { error ->
                        Toast.makeText(fragment.requireContext(), str(R.string.search_failed, error), Toast.LENGTH_LONG).show()
                        showFilters()
                    },
                    { values -> safely { show(values) } }
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
        choose(R.string.search_type_title, types.map { typeTitle(it) }, draft.type?.let { typeTitle(it) }) { index ->
            draft = draft.copy(type = index?.let { types[it] })
        }
    }

    companion object {
        private const val FIRST_YEAR = 1900
    }
}
