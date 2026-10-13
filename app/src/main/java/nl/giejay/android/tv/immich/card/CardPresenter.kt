package nl.giejay.android.tv.immich.card

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.presenter.AbstractPresenter
import timber.log.Timber


open class CardPresenter(
    context: Context,
    style: Int = R.style.DefaultCardTheme,
    private val imageWidthPx: Int? = null,
    private val imageHeightPx: Int? = null
) : AbstractPresenter<ImageCardView, ICard>(ContextThemeWrapper(context, style)) {

    override fun onBindViewHolder(card: ICard, cardView: ImageCardView) {
        // set the tag first, the asynchronous mosaic loading checks it to detect recycled views
        cardView.tag = card
        if (imageWidthPx != null && imageHeightPx != null) {
            cardView.setMainImageDimensions(imageWidthPx, imageHeightPx)
        }
        loadImage(card, cardView)

        cardView.titleText = card.title
        // always reset the content text, the view may be recycled from another card
        cardView.contentText = if (card.description.isNullOrEmpty()) null else card.description
        setSelected(cardView, card.selected)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        super.onUnbindViewHolder(viewHolder)
        if(context is Activity && context.isFinishing){
            return
        }
        try {
            val cardView = viewHolder.view as ImageCardView
            clearMosaicTargets(cardView)
            Glide.with(context).clear(cardView.mainImageView!!)
        } catch (e: IllegalArgumentException){
            Timber.e(e)
        }
    }

    open fun loadImage(card: ICard, cardView: ImageCardView) {
        clearMosaicTargets(cardView)
        if (card is Card && card.previewUrls.isNotEmpty()) {
            loadPreview(card, cardView)
            return
        }
        card.thumbnailUrl?.let {
            if(it.startsWith("http")){
                // restore the theme's scale type, the view may have shown a folder icon before
                defaultScaleType?.let { scaleType -> cardView.mainImageView!!.scaleType = scaleType }
                Glide.with(context)
                    .asBitmap()
                    .centerInside()
                    .load(it)
                    .into(cardView.mainImageView!!)
            } else {
                Glide.with(context).clear(cardView.mainImageView!!)
                cardView.mainImageView!!.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val resourceId = context.resources.getIdentifier(it, "drawable",
                    context.packageName);
                cardView.mainImageView!!.setImageResource(resourceId)
            }
        }
    }

    /**
     * Draws a folder preview: a single photo when there is one url, otherwise a 2x2 mosaic
     * like Kodi does.
     */
    private fun loadPreview(card: Card, cardView: ImageCardView) {
        val imageView = cardView.mainImageView!!
        Glide.with(context).clear(imageView)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        val urls = card.previewUrls.take(4)
        if (urls.size == 1) {
            Glide.with(context)
                .load(urls.first())
                .centerCrop()
                .into(imageView)
            return
        }

        val width = imageWidthPx ?: DEFAULT_MOSAIC_WIDTH
        val height = imageHeightPx ?: DEFAULT_MOSAIC_HEIGHT
        val gap = MOSAIC_GAP
        val cellWidth = (width - gap) / 2
        val cellHeight = (height - gap) / 2
        val mosaic = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaic)
        canvas.drawColor(Color.parseColor("#1A1A1A"))
        imageView.setImageBitmap(mosaic)

        val targets = urls.mapIndexed { index, url ->
            val left = (index % 2) * (cellWidth + gap)
            val top = (index / 2) * (cellHeight + gap)
            object : CustomTarget<Bitmap>(cellWidth, cellHeight) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // the view could have been recycled for another card in the meantime
                    if ((cardView.tag as? ICard)?.id != card.id) return
                    canvas.drawBitmap(resource, left.toFloat(), top.toFloat(), null)
                    imageView.invalidate()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // nothing to release, the pixels have already been copied in the mosaic
                }
            }.also {
                Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .centerCrop()
                    .into(it)
            }
        }
        imageView.setTag(R.id.mosaic_targets, targets)
    }

    private fun clearMosaicTargets(cardView: ImageCardView) {
        val imageView = cardView.mainImageView ?: return
        @Suppress("UNCHECKED_CAST")
        val targets = imageView.getTag(R.id.mosaic_targets) as? List<CustomTarget<Bitmap>> ?: return
        imageView.setTag(R.id.mosaic_targets, null)
        targets.forEach {
            try {
                Glide.with(context).clear(it)
            } catch (e: IllegalArgumentException) {
                Timber.e(e)
            }
        }
    }

    private var defaultScaleType: ImageView.ScaleType? = null

    override fun onCreateView(): ImageCardView {
        val view = ImageCardView(context)
        if (defaultScaleType == null) {
            defaultScaleType = view.mainImageView?.scaleType
        }
        return view
    }

    private fun setSelected(imageCardView: ImageCardView, selected: Boolean) {
        if(selected){
            imageCardView.mainImageView!!.background = context.getDrawable(R.drawable.border)
        } else {
            imageCardView.mainImageView!!.background = null
        }
    }

    companion object {
        private const val DEFAULT_MOSAIC_WIDTH = 412
        private const val DEFAULT_MOSAIC_HEIGHT = 232
        private const val MOSAIC_GAP = 4
    }
}
