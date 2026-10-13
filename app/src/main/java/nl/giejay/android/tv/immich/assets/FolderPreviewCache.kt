package nl.giejay.android.tv.immich.assets

import android.content.Context
import nl.giejay.android.tv.immich.ImmichApplication
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Preview of a folder in the folders view.
 *
 * @param assetIds ids of the assets used to draw the folder thumbnail (at most 4)
 * @param requested how many preview assets were looked for when this entry was built
 * @param ownItems number of assets directly inside the folder
 * @param timestamp when this entry was built
 */
data class FolderPreview(
    val assetIds: List<String>,
    val requested: Int,
    val ownItems: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isStale(): Boolean = System.currentTimeMillis() - timestamp > FolderPreviewCache.MAX_AGE_MILLIS

    /** True if this entry holds enough preview assets for a request of [wanted] assets. */
    fun satisfies(wanted: Int): Boolean = assetIds.size >= wanted || requested >= wanted
}

/**
 * Stores the folder previews on the device so the folders view does not have to query the
 * Immich server again for folders that were already displayed.
 */
object FolderPreviewCache {
    val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
    private const val FILE_NAME = "folder_previews.json"
    private const val PREFS_NAME = "folder_view"
    private const val LAST_PATH_KEY = "last_path"

    private val previews = ConcurrentHashMap<String, FolderPreview>()
    @Volatile
    private var loaded = false
    @Volatile
    private var dirty = false

    private val context: Context
        get() = ImmichApplication.appContext!!

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    fun get(path: String): FolderPreview? {
        ensureLoaded()
        return previews[path]
    }

    fun put(path: String, preview: FolderPreview) {
        previews[path] = preview
        dirty = true
    }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            json.keys().forEach { path ->
                val entry = json.getJSONObject(path)
                val ids = entry.getJSONArray("ids")
                previews[path] = FolderPreview(
                    (0 until ids.length()).map { ids.getString(it) },
                    entry.optInt("requested", ids.length()),
                    entry.optInt("own", 0),
                    entry.optLong("ts", 0)
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not read the folder previews, starting with an empty cache")
            previews.clear()
        }
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        dirty = false
        try {
            val json = JSONObject()
            previews.forEach { (path, preview) ->
                json.put(path, JSONObject()
                    .put("ids", JSONArray(preview.assetIds))
                    .put("requested", preview.requested)
                    .put("own", preview.ownItems)
                    .put("ts", preview.timestamp))
            }
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            Timber.e(e, "Could not store the folder previews")
        }
    }

    @Synchronized
    fun clear() {
        previews.clear()
        dirty = false
        loaded = true
        file.delete()
    }

    fun lastPath(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(LAST_PATH_KEY, null)

    fun saveLastPath(path: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (path == null) remove(LAST_PATH_KEY) else putString(LAST_PATH_KEY, path)
        }.apply()
    }
}
