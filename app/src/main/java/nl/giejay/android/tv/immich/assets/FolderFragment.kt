package nl.giejay.android.tv.immich.assets

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.leanback.widget.PresenterSelector
import androidx.navigation.fragment.findNavController
import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.album.AlbumDetailsFragmentDirections
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.Folder
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.card.CardPresenterSelector
import nl.giejay.android.tv.immich.shared.fragment.VerticalCardGridFragment
import nl.giejay.android.tv.immich.shared.prefs.FOLDER_COLUMNS
import nl.giejay.android.tv.immich.shared.prefs.FOLDER_PREVIEW_MODE
import nl.giejay.android.tv.immich.shared.prefs.FOLDER_REMEMBER_LAST
import nl.giejay.android.tv.immich.shared.prefs.FOLDER_SHOW_COUNT
import nl.giejay.android.tv.immich.shared.prefs.FOLDER_SKIP_SINGLE_CHILD
import nl.giejay.android.tv.immich.shared.prefs.MetaDataScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ANIMATE_ASSET_SLIDE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ANIMATION_SPEED
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DPAD_SEEK_IN_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_INTERVAL
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MERGE_PORTRAIT_PHOTOS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_PAN_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_SHOW_DATE_TOP_LEFT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_EFFECT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ZOOM_SCROLL_PANORAMAS
import nl.giejay.android.tv.immich.shared.util.toCard
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.util.LoadMoreResult
import nl.giejay.mediaslider.viewmodel.MediaSliderViewModel
import timber.log.Timber

data class Item(val item: Any) {
    fun isAsset() = item is Asset
    fun name() = if (item is Asset) item.deviceAssetId else (item as Folder).path
    fun asset() = item as Asset
    val id = if (item is Asset) item.id else (item as Folder).path
}

class FolderFragment : VerticalCardGridFragment<Item>() {
    private var rootFolder: Folder? = null
    private var stack = mutableListOf<Folder>()
    private val sliderViewModel: MediaSliderViewModel by activityViewModels()

    /** Folders that directly contain assets, as reported by Immich. Full paths. */
    private var pathsWithAssets: Set<String> = emptySet()

    /** Limits the number of concurrent calls to the Immich server while building previews. */
    private val serverCalls = Semaphore(MAX_CONCURRENT_CALLS)
    private var previewJob: Job? = null

    override val columns: Int
        get() = PreferenceManager.get(FOLDER_COLUMNS).coerceIn(3, 6)

    override fun createPresenterSelector(): PresenterSelector {
        // Keep the same total grid width as the original 4 columns of 206dp, so the grid always
        // fits on screen, and use a 4:3 ratio which suits the folder mosaics.
        val density = resources.displayMetrics.density
        val widthPx = (GRID_WIDTH_DP / columns * density).toInt()
        val heightPx = widthPx * 3 / 4
        return CardPresenterSelector(requireContext(), widthPx, heightPx)
    }

    override fun sortItems(items: List<Item>): List<Item> {
        return items.sortedWith({ i, i2 ->
            if (!i.isAsset() && !i2.isAsset() || (i.isAsset() && i2.isAsset())) {
                // both folders or both items
                i2.name()?.let { i.name()?.compareTo(it) } ?: 0
            } else if (!i.isAsset()) {
                // i is folder, i2 is asset, precedence for folder
                -1
            } else {
                // i2 is folder, i is asset
                1
            }
        })
    }

    override suspend fun loadItems(
        apiClient: ApiClient,
        page: Int,
        pageCount: Int
    ): Either<String, List<Item>> {
        if (rootFolder != null) {
            return Either.Right(emptyList())
        }
        return apiClient.listFoldersWithPaths().map { (tree, paths) ->
            pathsWithAssets = paths.toSet()
            FolderPreviewCache.ensureLoaded()

            stack.clear()
            val start = skipSingleChildren(tree)
            val restored = if (PreferenceManager.get(FOLDER_REMEMBER_LAST)) {
                FolderPreviewCache.lastPath()?.let { restorePath(start, it) }
            } else null
            val current = restored ?: start
            rootFolder = current

            val folderItems = folderItemsFor(current)
            val assets = if (current.getFullPath() in pathsWithAssets) {
                apiClient.listAssetsForFolder(current.getFullPath()).getOrElse { emptyList() }
            } else emptyList()
            withContext(Dispatchers.Main) {
                startPreviewLoading(current.children)
            }
            folderItems + assets.map { Item(it) }
        }
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no use case yet
    }

    override fun onItemClicked(card: Card) {
        if (card.thumbnailUrl?.startsWith("folder") == true) {
            if (card.title == "..") {
                goUp()
            } else {
                val clickedChild = rootFolder!!.children.find { it.path == card.title } ?: return
                stack.add(rootFolder!!)
                updateState(skipSingleChildren(clickedChild))
            }
        } else {
            openSlider(card)
        }
    }

    private fun openSlider(card: Card) {
        val findAllAssets = this.assets.filter { it.item is Asset }.map { it.item as Asset }
        val sliderItems = findAllAssets.toSliderItems(keepOrder = true, mergePortrait = PreferenceManager.get(SLIDER_MERGE_PORTRAIT_PHOTOS))
        val config = MediaSliderConfiguration(
            sliderItems.indexOfFirst { it.ids().contains(card.id) },
            PreferenceManager.get(SLIDER_INTERVAL),
            PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS),
            isVideoSoundEnable = true,
            sliderItems,
            { LoadMoreResult(emptyList(), false) },
            { item -> manualUpdatePosition(this.assets.indexOfFirst { item.ids().contains(it.id) }) },
            animationSpeedMillis = PreferenceManager.get(SLIDER_ANIMATION_SPEED),
            maxCutOffHeight = PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT),
            maxCutOffWidth = PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH),
            glideTransformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION),
            enableSlideAnimation = PreferenceManager.get(SCREENSAVER_ANIMATE_ASSET_SLIDE),
            gradiantOverlay = false,
            metaDataConfig = PreferenceManager.getAllMetaData(MetaDataScreen.VIEWER),
            zoomAndScrollPanorama = PreferenceManager.get(SLIDER_ZOOM_SCROLL_PANORAMAS),
            zoomEffectPercent = PreferenceManager.get(SLIDER_ZOOM_EFFECT),
            panEffectPercent = PreferenceManager.get(SLIDER_PAN_EFFECT),
            useLargeVideoBuffer = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO),
            dpadSeeksInVideo = PreferenceManager.get(SLIDER_DPAD_SEEK_IN_VIDEO),
            showDateTopLeft = PreferenceManager.get(SLIDER_SHOW_DATE_TOP_LEFT)
        )
        sliderViewModel.configuration = config
        findNavController().navigate(
            AlbumDetailsFragmentDirections.actionToPhotoSlider()
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // the Back key of the remote goes up one folder, like the ".." card
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (stack.isNotEmpty() && view.hasFocus()) {
                        goUp()
                    } else {
                        // not for us (side menu, top folder): let the app handle it as usual
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
    }

    /** Goes back to the parent folder and selects the folder we came from. */
    private fun goUp() {
        if (stack.isEmpty()) return
        val leaving = rootFolder
        val parent = stack.removeAt(stack.size - 1)
        // the folder we left may be deeper than a direct child when intermediate folders were skipped
        val cameFrom = leaving?.let { generateSequence(it) { f -> f.parent }.firstOrNull { f -> f.parent === parent } }
        updateState(parent, cameFrom)
    }

    private fun updateState(clickedChild: Folder, focusOn: Folder? = null) {
        previewJob?.cancel()
        rootFolder = clickedChild
        clearState()
        setData(folderItemsFor(clickedChild))
        val fullPath = clickedChild.getFullPath()
        if (fullPath in pathsWithAssets) {
            ioScope.launch {
                apiClient.listAssetsForFolder(fullPath).map {
                    setDataOnMain(it.map { Item(it) })
                }
            }
        }
        startPreviewLoading(clickedChild.children)
        val focusIndex = focusOn?.let { target -> assets.indexOfFirst { (it.item as? Folder) === target } } ?: -1
        if (focusIndex >= 0) {
            renderUntil(focusIndex)
            updateManualPositionHandler(focusIndex)
        } else {
            // select the first folder, after the ".." card if there is one
            updateManualPositionHandler(if (stack.isNotEmpty()) 1 else 0)
        }
    }

    private fun folderItemsFor(folder: Folder): List<Item> {
        val up = if (stack.isNotEmpty()) listOf(Item(Folder("..", mutableListOf(), null))) else emptyList()
        if (PreferenceManager.get(FOLDER_REMEMBER_LAST)) {
            FolderPreviewCache.saveLastPath(folder.getFullPath())
        }
        return up + folder.children.map { Item(it) }
    }

    /**
     * Goes down while a folder only holds a single sub folder and no assets of its own, so the
     * user does not have to open "data", "external", ... one by one.
     */
    private fun skipSingleChildren(folder: Folder): Folder {
        if (!PreferenceManager.get(FOLDER_SKIP_SINGLE_CHILD)) {
            return folder
        }
        var current = folder
        while (current.children.size == 1 && current.getFullPath() !in pathsWithAssets) {
            current = current.children.first()
        }
        return current
    }

    /**
     * Rebuilds the navigation stack from [start] down to the folder with the given full path, as if
     * the user had opened it by hand. Returns null if the folder does not exist anymore.
     */
    private fun restorePath(start: Folder, fullPath: String): Folder? {
        val target = findFolder(start, fullPath) ?: return null
        val chain = generateSequence(target) { it.parent }.takeWhile { it !== start }.toList().reversed()
        var current = start
        for (step in chain) {
            if (current === target) break
            if (!current.children.any { it === step }) {
                // step was skipped automatically while going down
                continue
            }
            stack.add(current)
            current = skipSingleChildren(step).let { skipped ->
                // never skip past the folder we want to restore
                if (isAncestorOrSelf(skipped, target)) skipped else step
            }
        }
        return current
    }

    private fun findFolder(from: Folder, fullPath: String): Folder? {
        val path = from.getFullPath()
        if (path == fullPath) return from
        if (path.isNotEmpty() && path != "/" && !fullPath.startsWith("$path/")) return null
        for (child in from.children) {
            findFolder(child, fullPath)?.let { return it }
        }
        return null
    }

    /** True if [folder] is [target] or one of the parents of [target]. */
    private fun isAncestorOrSelf(folder: Folder, target: Folder): Boolean =
        generateSequence(target) { it.parent }.any { it === folder }

    // ---------------------------------------------------------------------------------------
    // Folder previews
    // ---------------------------------------------------------------------------------------

    private fun previewCount(): Int = when (PreferenceManager.get(FOLDER_PREVIEW_MODE)) {
        0 -> 0
        1 -> 1
        else -> 4
    }

    private fun startPreviewLoading(folders: List<Folder>) {
        previewJob?.cancel()
        val wanted = previewCount()
        if (wanted == 0 && !PreferenceManager.get(FOLDER_SHOW_COUNT)) {
            return
        }
        // the item counts need the folder listing, so look for at least one asset in that case
        val toResolve = folders.sortedBy { it.path }.filter { folder ->
            val cached = FolderPreviewCache.get(folder.getFullPath())
            cached == null || cached.isStale() || !cached.satisfies(wanted.coerceAtLeast(1))
        }
        if (toResolve.isEmpty()) {
            return
        }
        previewJob = ioScope.launch {
            try {
                toResolve.map { folder ->
                    async {
                        // a preview that cannot be built must never take the whole app down
                        val preview = try {
                            resolvePreview(folder, wanted.coerceAtLeast(1), 0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Timber.e(e, "Could not build the preview of ${folder.path}")
                            null
                        }
                        if (preview != null) {
                            withContext(Dispatchers.Main) {
                                try {
                                    refreshCard(folder)
                                } catch (e: Exception) {
                                    Timber.e(e, "Could not refresh the card of ${folder.path}")
                                }
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                FolderPreviewCache.save()
            }
        }
    }

    /**
     * Finds up to [wanted] assets to represent the folder: its own photos when it has enough of
     * them, otherwise one photo from several sub folders spread over the folder, like Kodi.
     * Results are stored in [FolderPreviewCache], including those of the visited sub folders.
     */
    private suspend fun resolvePreview(folder: Folder, wanted: Int, depth: Int): FolderPreview? {
        val path = folder.getFullPath()
        FolderPreviewCache.get(path)?.let { cached ->
            if (!cached.isStale() && cached.satisfies(wanted)) return cached
        }
        val ownAssets = if (path in pathsWithAssets) {
            serverCalls.withPermit {
                apiClient.listAssetsForFolder(path)
            }.getOrElse { error ->
                Timber.e("Could not load the assets of $path: $error")
                return null
            }
        } else emptyList()

        val ids = pickSpread(ownAssets, wanted).toMutableList()
        if (ids.size < wanted && depth < MAX_DEPTH && folder.children.isNotEmpty()) {
            val children = spreadOver(folder.children.sortedBy { it.path }, wanted - ids.size)
            for (child in children) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val childPreview = resolvePreview(child, 1, depth + 1) ?: continue
                childPreview.assetIds.firstOrNull { it !in ids }?.let { ids.add(it) }
            }
        }
        val preview = FolderPreview(ids, wanted, ownAssets.size)
        FolderPreviewCache.put(path, preview)
        return preview
    }

    /** Takes [count] assets spread over the folder, photos first, in file name order. */
    private fun pickSpread(assets: List<Asset>, count: Int): List<String> {
        if (assets.isEmpty() || count <= 0) return emptyList()
        val photos = assets.filter { it.type == "IMAGE" }.ifEmpty { assets }
            .sortedBy { it.originalFileName ?: it.deviceAssetId ?: it.id }
        return spreadOver(photos, count).map { it.id }
    }

    /**
     * Takes [count] elements evenly spread over the list: first, last and in between.
     * Works on indexes only: [Folder] must never be hashed or compared, its generated
     * equals/hashCode follow the parent and children references and never end.
     */
    private fun <T> spreadOver(list: List<T>, count: Int): List<T> {
        if (list.size <= count) return list
        if (count <= 1) return listOf(list.first())
        return (0 until count)
            .map { it * (list.size - 1) / (count - 1) }
            .distinct()
            .map { list[it] }
    }

    private fun refreshCard(folder: Folder) {
        if (!isAdded || rootFolder?.children?.any { it === folder } != true) return
        for (index in 0 until adapter.size()) {
            val card = adapter.get(index) as? Card ?: continue
            if (card.title == folder.path && card.thumbnailUrl == FOLDER_ICON) {
                adapter.replace(index, createFolderCard(folder))
                return
            }
        }
    }

    override fun getBackgroundPicture(it: Item): String? {
        return null
    }

    override fun createCard(a: Item): Card {
        return if (a.item is Folder) {
            createFolderCard(a.item)
        } else {
            (a.item as Asset).toCard()
        }
    }

    private fun createFolderCard(folder: Folder): Card {
        if (folder.path == "..") {
            return Card("..", rootFolder?.path ?: "", "..", FOLDER_UP_ICON, null, false)
        }
        val preview = FolderPreviewCache.get(folder.getFullPath())
        val wanted = previewCount()
        val urls = if (wanted == 0 || preview == null) emptyList() else {
            // a single large photo needs the bigger preview, the mosaic cells only need thumbnails
            val size = if (wanted == 1) "preview" else "thumbnail"
            preview.assetIds.take(wanted).mapNotNull { ApiUtil.getThumbnailUrl(it, size) }
        }
        return Card(
            folder.path,
            if (PreferenceManager.get(FOLDER_SHOW_COUNT)) describe(folder, preview) else "",
            folder.path,
            FOLDER_ICON,
            urls.firstOrNull(),
            false,
            urls
        )
    }

    private fun describe(folder: Folder, preview: FolderPreview?): String {
        val parts = mutableListOf<String>()
        if (folder.children.isNotEmpty()) {
            parts += resources.getQuantityString(R.plurals.folder_count_folders, folder.children.size, folder.children.size)
        }
        val items = preview?.ownItems ?: 0
        if (items > 0) {
            parts += resources.getQuantityString(R.plurals.folder_count_items, items, items)
        }
        return parts.joinToString(" · ")
    }

    override fun onDestroy() {
        previewJob?.cancel()
        FolderPreviewCache.save()
        super.onDestroy()
    }

    companion object {
        private const val FOLDER_ICON = "folder"
        private const val FOLDER_UP_ICON = "folder_up"
        /** Total width of the grid in dp, 4 cards of 206dp in the original layout. */
        private const val GRID_WIDTH_DP = 4 * 206f
        private const val MAX_CONCURRENT_CALLS = 4
        /** How deep we look in sub folders to find a photo for a folder thumbnail. */
        private const val MAX_DEPTH = 6
    }
}
