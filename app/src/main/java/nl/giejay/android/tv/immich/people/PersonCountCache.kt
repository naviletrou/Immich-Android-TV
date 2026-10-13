package nl.giejay.android.tv.immich.people

import android.content.Context
import nl.giejay.android.tv.immich.ImmichApplication
import java.util.concurrent.ConcurrentHashMap

/**
 * Number of photos of each person, kept on the device so the people view can sort and show the
 * counts immediately. Refreshed in the background each time the people view is opened.
 */
object PersonCountCache {
    private const val PREFS_NAME = "person_counts"
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile
    private var loaded = false

    private val prefs
        get() = ImmichApplication.appContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        prefs.all.forEach { (id, value) -> (value as? Int)?.let { counts[id] = it } }
    }

    fun get(personId: String): Int? {
        ensureLoaded()
        return counts[personId]
    }

    fun put(personId: String, count: Int) {
        ensureLoaded()
        if (counts[personId] == count) return
        counts[personId] = count
        prefs.edit().putInt(personId, count).apply()
    }
}
