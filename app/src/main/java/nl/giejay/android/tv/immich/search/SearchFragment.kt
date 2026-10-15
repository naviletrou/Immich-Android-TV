package nl.giejay.android.tv.immich.search

import arrow.core.Either
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment

/**
 * "Search" page of the side menu: a card to start a new search and one to show the last search again.
 * The results open on their own screen, the Back key comes back here.
 */
class SearchFragment : VerticalCardGridFragment<Card>() {

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Card>> {
        if (page != startPage) return Either.Right(emptyList())
        return Either.Right(cards())
    }

    private fun cards(): List<Card> {
        val context = context ?: return emptyList()
        val last = SearchFilters.loadLast(context)
        val cards = mutableListOf(
            Card(getString(R.string.search_new), getString(R.string.search_choose_filters), NEW_ID,
                SearchResultsFragment.SEARCH_ICON, null, false)
        )
        if (!last.isEmpty()) {
            cards += Card(getString(R.string.search_last), last.summary(context), LAST_ID,
                SearchResultsFragment.SEARCH_ICON, null, false)
        }
        return cards
    }

    override fun onResume() {
        super.onResume()
        // the last search may have changed in the results screen
        if (assets.isNotEmpty()) {
            val fresh = cards()
            if (fresh != assets) {
                clearState()
                setData(fresh)
            }
        }
    }

    override fun sortItems(items: List<Card>): List<Card> = items

    override fun createCard(a: Card): Card = a

    override fun getBackgroundPicture(it: Card): String? = null

    override fun onItemSelected(card: Card, indexOf: Int) {
        // nothing to do
    }

    override fun onItemClicked(card: Card) {
        if (!isAdded) return
        val last = SearchFilters.loadLast(requireContext())
        when (card.id) {
            LAST_ID -> SearchResultsFragment.open(this, last)
            else -> SearchDialog(this, apiClient, ioScope, SearchFilters(mode = last.searchMode), null) { filters ->
                SearchResultsFragment.open(this, filters)
            }.show()
        }
    }

    companion object {
        private const val NEW_ID = "search_new"
        private const val LAST_ID = "search_last"
    }
}
