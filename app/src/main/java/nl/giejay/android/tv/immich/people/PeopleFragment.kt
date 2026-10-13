package nl.giejay.android.tv.immich.people

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.leanback.widget.PresenterSelector
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenterSelector
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.shared.prefs.PEOPLE_COLUMNS
import nl.giejay.android.tv.immich.shared.prefs.PEOPLE_SHOW_COUNT
import nl.giejay.android.tv.immich.shared.prefs.PEOPLE_SORTING
import nl.giejay.android.tv.immich.shared.prefs.PeopleOrder
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber
import java.text.Normalizer
import java.util.UUID

class PeopleFragment : VerticalCardGridFragment<Person>() {

    /** Limits the number of concurrent calls to the server while counting the photos. */
    private val serverCalls = Semaphore(MAX_CONCURRENT_CALLS)

    /** Everybody, before the search filter is applied. */
    private var allPeople: List<Person> = emptyList()
    private var query: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // the Back key first clears an active search
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (query.isNotEmpty() && view.hasFocus()) {
                        setQuery("")
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
    }

    override val columns: Int
        get() = PreferenceManager.get(PEOPLE_COLUMNS).coerceIn(4, 10)

    override fun createPresenterSelector(): PresenterSelector {
        // square face thumbnails, the grid keeps the width of the original 4 columns of 206dp
        val density = resources.displayMetrics.density
        val sizePx = (4 * 206f / columns * density).toInt()
        return CardPresenterSelector(requireContext(), sizePx, sizePx)
    }

    private fun countsNeeded(): Boolean =
        PreferenceManager.get(PEOPLE_SORTING) == PeopleOrder.MOST_PHOTOS || PreferenceManager.get(PEOPLE_SHOW_COUNT)

    override fun sortItems(items: List<Person>): List<Person> {
        // the search card always stays first
        val (search, people) = items.partition { it.id == SEARCH_PERSON_ID }
        return search + sortPeople(people)
    }

    private fun sortPeople(items: List<Person>): List<Person> {
        return when (PreferenceManager.get(PEOPLE_SORTING)) {
            PeopleOrder.MOST_PHOTOS -> items.sortedWith(
                compareByDescending<Person> { PersonCountCache.get(it.id.toString()) ?: -1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            PeopleOrder.NAME_ASC -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
            PeopleOrder.NAME_DESC -> items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name ?: "" })
        }
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Person>> {
        if (page != startPage) {
            // no pagination possible yet!
            return Either.Right(emptyList())
        }
        return apiClient.listPeople().map { people ->
            if (countsNeeded()) {
                // the first time, wait for the counts so the list can be sorted by them
                val missing = people.filter { PersonCountCache.get(it.id.toString()) == null }
                if (missing.isNotEmpty()) {
                    fetchCounts(apiClient, missing)
                }
                // the following times show the stored counts immediately and refresh them quietly
                refreshCountsLater(people.filter { it !in missing })
            }
            allPeople = people
            listOf(searchPerson) + filtered(people)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    private fun filtered(people: List<Person>): List<Person> {
        if (query.isEmpty()) return people
        val wanted = normalize(query)
        return people.filter { normalize(it.name ?: "").contains(wanted) }
    }

    /** Lower case and without accents, so "elodie" finds "Élodie". */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

    private fun setQuery(newQuery: String) {
        val cleaned = newQuery.trim()
        if (cleaned == query) return
        query = cleaned
        clearState()
        setData(listOf(searchPerson) + filtered(allPeople))
        updateManualPositionHandler(0)
    }

    private fun showSearchDialog() {
        val context = requireContext()
        val previous = query
        val input = EditText(context).apply {
            setText(query)
            setSelection(text.length)
            hint = getString(R.string.people_search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.people_search)
            .setView(container)
            .setPositiveButton(R.string.people_search) { _, _ -> }
            .setNeutralButton(R.string.people_search_clear) { _, _ -> setQuery("") }
            .setNegativeButton(R.string.people_search_cancel) { _, _ -> setQuery(previous) }
            .create()
        // the grid behind the dialog is filtered while typing
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                setQuery(s?.toString() ?: "")
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss()
                true
            } else false
        }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private suspend fun fetchCounts(apiClient: ApiClient, people: List<Person>): List<Person> = coroutineScope {
        people.map { person ->
            async {
                try {
                    serverCalls.withPermit {
                        apiClient.getPersonAssetCount(person.id.toString())
                    }.fold(
                        { error -> Timber.e("Could not count the photos of ${person.name}: $error"); null },
                        { count ->
                            val changed = PersonCountCache.get(person.id.toString()) != count
                            PersonCountCache.put(person.id.toString(), count)
                            person.takeIf { changed }
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Could not count the photos of ${person.name}")
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun refreshCountsLater(people: List<Person>) {
        if (people.isEmpty()) return
        ioScope.launch {
            val changed = fetchCounts(apiClient, people)
            if (changed.isEmpty() || !PreferenceManager.get(PEOPLE_SHOW_COUNT)) return@launch
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                // only update the texts, the order changes the next time the view is opened
                val changedIds = changed.map { it.id.toString() }.toSet()
                for (index in 0 until adapter.size()) {
                    val card = adapter.get(index) as? Card ?: continue
                    if (card.id in changedIds) {
                        adapter.replace(index, card.copy(description = describe(card.id)))
                    }
                }
            }
        }
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
       // no use case yet
    }

    override fun onItemClicked(card: Card) {
        if (card.id == SEARCH_CARD_ID) {
            showSearchDialog()
            return
        }
        findNavController().navigate(
            HomeFragmentDirections.actionHomeFragmentToPersonAssetsFragment(
                card.id,
                card.title
            )
        )
    }

    override fun getBackgroundPicture(it: Person): String? {
        return null
    }

    private fun describe(personId: String): String {
        if (!PreferenceManager.get(PEOPLE_SHOW_COUNT)) return ""
        val count = PersonCountCache.get(personId) ?: return ""
        return resources.getQuantityString(R.plurals.person_count_photos, count, count)
    }

    override fun createCard(a: Person): Card {
        if (a.id == SEARCH_PERSON_ID) {
            val shown = assets.count { it.id != SEARCH_PERSON_ID }
            return Card(
                if (query.isEmpty()) getString(R.string.people_search) else getString(R.string.people_search_active, query),
                if (query.isEmpty()) "" else resources.getQuantityString(R.plurals.people_search_results, shown, shown),
                SEARCH_CARD_ID,
                SEARCH_ICON,
                null,
                false
            )
        }
        return Card(
            a.name ?: "Unknown",
            describe(a.id.toString()),
            a.id.toString(),
            ApiUtil.getPersonThumbnail(a.id),
            ApiUtil.getPersonThumbnail(a.id),
            false
        )
    }

    companion object {
        private const val MAX_CONCURRENT_CALLS = 6
        private const val SEARCH_CARD_ID = "search"
        private const val SEARCH_ICON = "people_search"
        private val SEARCH_PERSON_ID = UUID(0L, 0L)
        /** Placeholder item for the search card at the start of the grid. */
        private val searchPerson = Person(null, SEARCH_PERSON_ID, "")
        private val DIACRITICS = "\\p{Mn}+".toRegex()
    }
}
