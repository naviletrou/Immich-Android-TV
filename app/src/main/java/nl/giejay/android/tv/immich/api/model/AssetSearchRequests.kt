package nl.giejay.android.tv.immich.api.model

import java.util.UUID

/**
 * Body of POST search/smart (search on the content of the photos, needs machine learning on the server).
 * Only properties known by the server: Immich v3 rejects unknown ones. Null values are not sent.
 */
data class SmartSearchRequest(
    val query: String,
    val page: Int,
    val size: Int,
    val language: String? = null,
    val city: String? = null,
    val country: String? = null,
    val make: String? = null,
    val personIds: List<UUID>? = null,
    val takenAfter: String? = null,
    val takenBefore: String? = null,
    val type: String? = null,
    val isFavorite: Boolean? = null,
    val withExif: Boolean = true,
    val visibility: String? = "timeline"
)

/** Body of POST search/metadata used by the search view (file name, description, text in the photo…). */
data class FilterSearchRequest(
    val page: Int,
    val size: Int,
    val order: String? = "desc",
    val originalFileName: String? = null,
    val description: String? = null,
    val ocr: String? = null,
    val city: String? = null,
    val country: String? = null,
    val make: String? = null,
    val personIds: List<UUID>? = null,
    val takenAfter: String? = null,
    val takenBefore: String? = null,
    val type: String? = null,
    val isFavorite: Boolean? = null,
    val withExif: Boolean = true,
    val visibility: String? = "timeline"
)
