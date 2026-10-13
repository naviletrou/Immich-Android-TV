package nl.giejay.android.tv.immich.people

import androidx.navigation.fragment.findNavController
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Person
import nl.giejay.android.tv.immich.api.util.ApiUtil
import androidx.leanback.widget.PresenterSelector
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenterSelector
import nl.giejay.android.tv.immich.shared.prefs.PEOPLE_COLUMNS
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment

class PeopleFragment : VerticalCardGridFragment<Person>() {

    override val columns: Int
        get() = PreferenceManager.get(PEOPLE_COLUMNS).coerceIn(4, 10)

    override fun createPresenterSelector(): PresenterSelector {
        // square face thumbnails, the grid keeps the width of the original 4 columns of 206dp
        val density = resources.displayMetrics.density
        val sizePx = (4 * 206f / columns * density).toInt()
        return CardPresenterSelector(requireContext(), sizePx, sizePx)
    }

    override fun sortItems(items: List<Person>): List<Person> {
       return items
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Person>> {
        if (page == startPage) {
            // no pagination possible yet!
            return apiClient.listPeople()
        }
        return Either.Right(emptyList())
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

    override fun createCard(a: Person): Card {
        return Card(
            a.name ?: "Unknown",
            "",
            a.id.toString(),
            ApiUtil.getPersonThumbnail(a.id),
            ApiUtil.getPersonThumbnail(a.id),
            false
        )
    }
}