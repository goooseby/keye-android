package io.github.goooseby.keye

import io.github.goooseby.keye.data.BlackBorders
import io.github.goooseby.keye.data.detectBlackBorders
import org.junit.Assert.assertEquals
import org.junit.Test

class BlackBordersTest {
    @Test fun detectsOpposingBarsOnEitherAxisOrBoth() {
        assertEquals(BlackBorders(24, 0, 24, 0), Page(24, 0, 24, 0).detect())
        assertEquals(BlackBorders(0, 16, 0, 16), Page(0, 16, 0, 16).detect())
        assertEquals(BlackBorders(24, 16, 24, 16), Page(24, 16, 24, 16).detect())
    }

    @Test fun keepsSingleSidedUnevenOrThinEdges() {
        assertEquals(BlackBorders.None, Page(24, 0, 0, 0).detect())
        assertEquals(BlackBorders.None, Page(24, 0, 38, 0).detect())
        assertEquals(BlackBorders.None, Page(2, 0, 2, 0).detect())
    }

    @Test fun keepsDarkSlidesAndBarsContainingDetailsOutsideProbeLines() {
        assertEquals(BlackBorders.None, Page(content = 0xff25252a.toInt()).detect())
        assertEquals(BlackBorders.None, Page(24, 0, 24, 0, content = 0xff25252a.toInt()).detect())
        assertEquals(BlackBorders.None, Page(content = 0xff000000.toInt()).detect())
        val marked = Page(24, 0, 24, 0).apply { pixels[3 * width + 7] = 0xffa05428.toInt() }
        assertEquals(BlackBorders.None, marked.detect())
    }

    @Test fun preservesTheInnerPixelWhenOnlyTheBoundaryContainsNoise() {
        val marked = Page(24, 0, 24, 0).apply { pixels[3 * width + 23] = 0xff232323.toInt() }
        assertEquals(BlackBorders(23, 0, 24, 0), marked.detect())
    }

    private class Page(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, content: Int = 0xffebebeb.toInt()) {
        val width = 240
        private val height = 160
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x < left || x >= width - right || y < top || y >= height - bottom) 0xff101010.toInt() else content
        }

        fun detect() = detectBlackBorders(width, height) { x, y, w, h, out ->
            repeat(h) { row -> pixels.copyInto(out, row * w, (y + row) * width + x, (y + row) * width + x + w) }
        }
    }
}
