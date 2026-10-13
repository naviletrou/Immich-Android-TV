package nl.giejay.android.tv.immich.card

data class Card(
    override val title: String,
    override val description: String?,
    override val id: String,
    override val thumbnailUrl: String?,
    override val backgroundUrl: String?,
    override var selected: Boolean = false,
    /**
     * Optional list of thumbnail urls used to draw a preview of a folder.
     * One url is drawn full size, 2 to 4 urls are drawn as a 2x2 mosaic.
     */
    val previewUrls: List<String> = emptyList()) : ICard
