package nl.giejay.android.tv.immich.search

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.SearchResponse
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import timber.log.Timber

/**
 * Results of a search, on their own screen: the Back key returns to where the search was started
 * (folder or search view). The first card shows the search and lets the user change it.
 */
class SearchResultsFragment : GenericAssetFragment() {

    private var filters: SearchFilters = SearchFilters()
    private var total: Int? = null
    private var nextCursor: String? = null
    private var failed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        filters = SearchFilters.fromJson(arguments?.getString(ARG_FILTERS))
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
            nextCursor = null
        } else if (nextCursor == null) {
            return Either.Right(emptyList())
        }
        val result: Either<String, SearchResponse> = try {
            if (current.usesSmartSearch) {
                // no pagination for the smart search: the closest results at once
                apiClient.smartSearch(current.toSmartRequest(SMART_RESULTS))
            } else {
                apiClient.filterSearch(current.toMetadataRequest(pageCount, nextCursor,
                    newestFirst = currentSort != PhotosOrder.OLDEST_NEWEST))
            }
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            Either.Left(e.message ?: e.javaClass.simpleName)
        }
        return result.fold(
            { error ->
                nextCursor = null
                if (first) failed = true
                showError(error)
                // the search card must stay, so the search can be changed
                Either.Right(if (first) listOf(searchAsset) else emptyList())
            },
            { response ->
                val items = response.assets.items ?: emptyList()
                nextCursor = if (current.usesSmartSearch) null else response.assets.nextCursor
                if (first) {
                    total = response.assets.total ?: if (nextCursor == null) items.size else null
                    Either.Right(listOf(searchAsset) + items)
                } else {
                    Either.Right(items)
                }
            }
        )
    }

    override fun allPagesLoaded(items: List<Asset>): Boolean = nextCursor == null

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

    private suspend fun showError(error: String) = withContext(Dispatchers.Main) {
        if (!isAdded) return@withContext
        val message = if (filters.usesSmartSearch) getString(R.string.search_smart_failed, error)
        else getString(R.string.search_failed, error)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun applyFilters(newFilters: SearchFilters) {
        if (!isAdded) return
        filters = newFilters
        SearchFilters.saveLast(requireContext(), newFilters)
        arguments = bundleOf(ARG_FILTERS to newFilters.toJson())
        clearState()
        allPagesLoaded = false
        nextCursor = null
        title = ""
        fetchInitialItems()
    }

    // ---------------------------------------------------------------------------------------
    // Cards and title
    // ---------------------------------------------------------------------------------------

    private fun countText(): String = when {
        failed -> getString(R.string.search_error_short)
        total == 0 -> getString(R.string.search_no_result)
        total != null -> resources.getQuantityString(R.plurals.search_results, total!!, total!!)
        else -> getString(R.string.search_by_relevance)
    }

    override fun setTitle(response: List<Asset>) {
        val summary = filters.summary(requireContext())
        val titleText = titleView?.findViewById<TextView>(androidx.leanback.R.id.title_text)
        if (titleText != null) {
            titleText.isSingleLine = false
            titleText.maxLines = 2
            title = "$summary\n${countText()}"
        } else {
            title = "$summary · ${countText()}"
        }
    }

    override fun createCard(a: Asset): Card {
        if (a.id != SEARCH_CARD_ID) return super.createCard(a)
        return Card(getString(R.string.search_change), countText(), SEARCH_CARD_ID, SEARCH_ICON, null, false)
    }

    override fun getBackgroundPicture(it: Asset): String? =
        if (it.id == SEARCH_CARD_ID) null else super.getBackgroundPicture(it)

    override fun onItemClicked(card: Card) {
        if (card.id == SEARCH_CARD_ID) {
            val folder = filters.folderPath?.let { it to (filters.folderName ?: it) }
            SearchDialog(this, apiClient, ioScope, filters, folder) { applyFilters(it) }.show()
        } else {
            super.onItemClicked(card)
        }
    }

    companion object {
        const val ARG_FILTERS = "filters"
        private const val SEARCH_CARD_ID = "search"
        const val SEARCH_ICON = "people_search"
        /** Number of results of the smart search (it has no pagination). */
        private const val SMART_RESULTS = 250

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

        /** Remembers the search and opens its results. */
        fun open(from: Fragment, filters: SearchFilters) {
            if (!from.isAdded) return
            SearchFilters.saveLast(from.requireContext(), filters)
            from.findNavController().navigate(R.id.action_global_search_results, bundleOf(ARG_FILTERS to filters.toJson()))
        }
    }
}
