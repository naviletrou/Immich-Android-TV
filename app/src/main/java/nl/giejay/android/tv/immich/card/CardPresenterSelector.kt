package nl.giejay.android.tv.immich.card

import android.content.Context
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector

/**
 * Always returns the same presenter instance. Leanback maps each presenter instance to a view type,
 * so returning a new instance per item prevents the grid from recycling card views while scrolling.
 *
 * @param imageWidthPx optional fixed width of the card image, in pixels
 * @param imageHeightPx optional fixed height of the card image, in pixels
 */
class CardPresenterSelector(
    val context: Context,
    private val imageWidthPx: Int? = null,
    private val imageHeightPx: Int? = null
) : PresenterSelector() {
    private val presenter: CardPresenter by lazy {
        CardPresenter(context, imageWidthPx = imageWidthPx, imageHeightPx = imageHeightPx)
    }

    override fun getPresenter(item: Any?): Presenter {
        return presenter
    }

    override fun getPresenters(): Array<Presenter> {
        return arrayOf(presenter)
    }
}
