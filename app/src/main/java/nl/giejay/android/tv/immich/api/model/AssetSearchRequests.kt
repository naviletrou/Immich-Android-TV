package nl.giejay.android.tv.immich.api.model

/**
 * Body of POST search/smart in the Immich v3 shape (search on the content of the photos, needs machine
 * learning on the server). The filters go in [filter]; the old flat fields must not be sent with it.
 * The smart search of v3 has no pagination: the [size] closest results are returned.
 */
data class SmartSearchV3Request(
    val query: String,
    val size: Int,
    val language: String? = null,
    val withExif: Boolean = true,
    val filter: Map<String, Any>
)

/** Body of POST search/metadata in the Immich v3 shape, paginated with [cursor]. */
data class MetadataSearchV3Request(
    val size: Int,
    val cursor: String? = null,
    val withExif: Boolean = true,
    val orderBy: Map<String, String>,
    val filter: Map<String, Any>
)
