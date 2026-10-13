package nl.giejay.android.tv.immich.people

import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.PERSON_PHOTOS_SORTING
import nl.giejay.android.tv.immich.shared.prefs.PersonAssetsSettingsScreen
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import java.util.UUID

class PersonAssetsFragment : GenericAssetFragment() {
    private lateinit var personId: String
    private lateinit var personName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        personId = PersonAssetsFragmentArgs.fromBundle(requireArguments()).personId
        personName = PersonAssetsFragmentArgs.fromBundle(requireArguments()).personName
        super.onCreate(savedInstanceState)
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Asset>> {
        // chronological, the direction is chosen in the settings (used to be random)
        return apiClient.listAssets(page,
            pageCount,
            random = false,
            order = if (currentSort == PhotosOrder.NEWEST_OLDEST) "desc" else "asc",
            contentType = currentFilter,
            personIds = listOf(UUID.fromString(personId))).map { it.assets }
    }

    override fun getSortingKey(): EnumByTitlePref<PhotosOrder> {
        return PERSON_PHOTOS_SORTING
    }

    override fun openPopUpMenu() {
        findNavController().navigate(
            HomeFragmentDirections.actionGlobalToSettingsDialog(PersonAssetsSettingsScreen.key)
        )
    }

    override fun showMediaCount(): Boolean {
        return false
    }


    override fun setTitle(response: List<Asset>) {
        showTitleWithCount(PersonCountCache.get(personId))
        // refresh the count, it may have changed since the people view was opened
        ioScope.launch {
            apiClient.getPersonAssetCount(personId).map { count ->
                PersonCountCache.put(personId, count)
                withContext(Dispatchers.Main) {
                    if (isAdded) showTitleWithCount(count)
                }
            }
        }
    }

    /** Shows the number of photos under the name, in the title at the top right. */
    private fun showTitleWithCount(count: Int?) {
        if (count == null) {
            title = personName
            return
        }
        val countText = resources.getQuantityString(R.plurals.person_count_photos, count, count)
        val titleText = titleView?.findViewById<TextView>(androidx.leanback.R.id.title_text)
        if (titleText != null) {
            titleText.isSingleLine = false
            titleText.maxLines = 2
            title = "$personName\n$countText"
        } else {
            title = "$personName · $countText"
        }
    }
}
