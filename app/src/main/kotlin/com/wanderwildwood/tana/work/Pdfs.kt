package com.wanderwildwood.tana.work

import kotlin.math.max
import kotlin.math.min

/**
 * What a PDF reader needs to work out without Android: which files are PDFs, what a typed page
 * number means, and where on the screen the page goes. The reading itself is [PdfBook].
 */
object Pdfs {

    const val MIME = "application/pdf"

    fun canRead(name: String) = Names.extension(name) == "pdf"

    /**
     * A page number as typed, 1 for the first, turned into the page's place from 0; null when
     * it is not a page in a book of [count] pages. Spaces either side are let through, since a
     * phone's keyboard adds them.
     */
    fun pageFrom(typed: String, count: Int): Int? {
        val n = typed.trim().toIntOrNull() ?: return null
        return if (n in 1..count) n - 1 else null
    }
}

/**
 * Where a page sits on the screen: its top left corner, in pixels of the space it is shown
 * in, and how far it is zoomed past the size that fits that space whole. At [zoom] 1 the page
 * fits, centred; above 1 it is bigger than the space and can be moved about.
 *
 * A page's own size is in PDF points, and the size that fits is worked out from it every time
 * rather than kept, so turning the phone round needs nothing more than the new space.
 */
data class Frame(val zoom: Float, val left: Float, val top: Float) {

    companion object {
        /** Past this the print is as big as anyone wants it, and the picture is mostly blur. */
        const val MOST = 5f

        /** How many pixels a point is at zoom 1: the page as big as fits, whole. */
        fun fitScale(pageWidth: Float, pageHeight: Float, boxWidth: Float, boxHeight: Float): Float =
            min(boxWidth / pageWidth, boxHeight / pageHeight)

        /** The page whole, in the middle of the space. */
        fun fit(pageWidth: Float, pageHeight: Float, boxWidth: Float, boxHeight: Float): Frame {
            val s = fitScale(pageWidth, pageHeight, boxWidth, boxHeight)
            return Frame(1f, (boxWidth - pageWidth * s) / 2, (boxHeight - pageHeight * s) / 2)
        }
    }

    /**
     * Two fingers moved: the page grows by [factor] about [centreX], [centreY], so whatever
     * was under the fingers stays under them, and goes with them by [panX], [panY]. Zoomed
     * back to fit or below, it settles at fit.
     */
    fun pinched(
        factor: Float,
        centreX: Float,
        centreY: Float,
        panX: Float,
        panY: Float,
        pageWidth: Float,
        pageHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
    ): Frame {
        val wanted = (zoom * factor).coerceIn(1f, MOST)
        if (wanted <= 1.01f) return fit(pageWidth, pageHeight, boxWidth, boxHeight)
        val k = wanted / zoom
        return Frame(
            wanted,
            centreX - (centreX - left) * k + panX,
            centreY - (centreY - top) * k + panY,
        ).kept(pageWidth, pageHeight, boxWidth, boxHeight)
    }

    /** One finger moved, with the page zoomed: the page goes with it, as far as its edges. */
    fun moved(dx: Float, dy: Float, pageWidth: Float, pageHeight: Float, boxWidth: Float, boxHeight: Float): Frame =
        copy(left = left + dx, top = top + dy).kept(pageWidth, pageHeight, boxWidth, boxHeight)

    /**
     * The page kept in sight: a side narrower than the space is centred on it, and a side
     * wider never leaves a gap between its edge and the screen's.
     */
    fun kept(pageWidth: Float, pageHeight: Float, boxWidth: Float, boxHeight: Float): Frame {
        val s = fitScale(pageWidth, pageHeight, boxWidth, boxHeight) * zoom
        fun along(at: Float, page: Float, box: Float): Float =
            if (page <= box) (box - page) / 2 else at.coerceIn(box - page, 0f)
        return Frame(zoom, along(left, pageWidth * s, boxWidth), along(top, pageHeight * s, boxHeight))
    }

    /** Pixels a point is in this frame. */
    fun scale(pageWidth: Float, pageHeight: Float, boxWidth: Float, boxHeight: Float): Float =
        fitScale(pageWidth, pageHeight, boxWidth, boxHeight) * max(zoom, 1f)
}
