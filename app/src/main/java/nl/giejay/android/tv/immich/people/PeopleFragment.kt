package nl.giejay.android.tv.immich.people

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

class PeopleFragment : VerticalCardGridFragment<Person>() {

    /** Limits the number of concurrent calls to the server while counting the photos. */
    private val serverCalls = Semaphore(MAX_CONCURRENT_CALLS)

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
            people
        }
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
    }
}
