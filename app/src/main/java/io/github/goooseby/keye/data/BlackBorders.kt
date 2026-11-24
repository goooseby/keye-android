package io.github.goooseby.keye.data

import kotlin.math.abs

internal data class BlackBorders(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object { val None = BlackBorders(0, 0, 0, 0) }
}

internal fun interface BorderPixels {
    fun read(x: Int, y: Int, width: Int, height: Int, destination: IntArray)
}

/** Detect only opposing, nearly even bars with a straight, high-contrast inner edge. */
internal fun detectBlackBorders(width: Int, height: Int, pixels: BorderPixels): BlackBorders {
    if (width < 64 || height < 64) return BlackBorders.None
    val horizontal = detectPair(width, height, vertical = false, pixels)
    val vertical = detectPair(height, width, vertical = true, pixels)
    return BlackBorders(horizontal.first, vertical.first, horizontal.second, vertical.second)
}

private val noPair = 0 to 0
private const val probeCount = 24

private fun detectPair(length: Int, span: Int, vertical: Boolean, pixels: BorderPixels): Pair<Int, Int> {
    val minThickness = maxOf(4, length / 100)
    val maxThickness = length * 22 / 100
    val line = IntArray(length)
    var minStart = length
    var maxStart = 0
    var minEnd = length
    var maxEnd = 0
    var brightStart = 0
    var brightEnd = 0

    // Probe the middle half, outside any bars along the perpendicular axis.
    repeat(probeCount) { probe ->
        val position = span / 4 + probe * (span / 2) / (probeCount - 1)
        if (vertical) pixels.read(position, 0, 1, length, line) else pixels.read(0, position, length, 1, line)
        val start = darkRun(line, maxThickness, fromEnd = false)
        val end = darkRun(line, maxThickness, fromEnd = true)
        if (start !in minThickness..maxThickness || end !in minThickness..maxThickness) return noPair
        minStart = minOf(minStart, start)
        maxStart = maxOf(maxStart, start)
        minEnd = minOf(minEnd, end)
        maxEnd = maxOf(maxEnd, end)
        if (brightTransition(line, start, 1)) brightStart++
        if (brightTransition(line, length - 1 - end, -1)) brightEnd++
    }

    if (maxStart - minStart > maxOf(2, minStart / 16) || maxEnd - minEnd > maxOf(2, minEnd / 16)) return noPair
    if (abs(minStart - minEnd) > maxOf(3, minOf(minStart, minEnd) / 6)) return noPair
    if (brightStart < 21 || brightEnd < 21) return noPair

    // Recheck every removed pixel. On even bars, leave a one-pixel inner fringe if decoding added edge noise.
    val fringe = if (abs(minStart - minEnd) <= 1) 1 else 0
    val start = verifiedThickness(0, 0, if (vertical) span else minStart, if (vertical) minStart else span,
        vertical, fromEnd = false, fringe, pixels)
    if (start < minThickness) return noPair
    val end = verifiedThickness(if (vertical) 0 else length - minEnd, if (vertical) length - minEnd else 0,
        if (vertical) span else minEnd, if (vertical) minEnd else span, vertical, fromEnd = true, fringe, pixels)
    return if (end >= minThickness) start to end else noPair
}

private fun darkRun(line: IntArray, limit: Int, fromEnd: Boolean): Int {
    var run = 0
    while (run <= limit && almostBlack(line[if (fromEnd) line.lastIndex - run else run])) run++
    return run
}

private fun brightTransition(line: IntArray, index: Int, direction: Int): Boolean {
    var bright = 0
    repeat(6) { step ->
        val pixel = line[index + step * direction]
        if (pixel ushr 24 == 255 && (pixel and 255 >= 72 || pixel ushr 8 and 255 >= 72 || pixel ushr 16 and 255 >= 72)) bright++
    }
    return bright >= 4
}

private fun almostBlack(pixel: Int): Boolean =
    pixel ushr 24 == 255 && pixel and 255 <= 26 && pixel ushr 8 and 255 <= 26 && pixel ushr 16 and 255 <= 26

private fun verifiedThickness(x: Int, y: Int, width: Int, height: Int, vertical: Boolean, fromEnd: Boolean,
    fringe: Int, pixels: BorderPixels): Int {
    val thickness = if (vertical) height else width
    var verified = thickness
    val rowsPerRead = (16384 / width).coerceIn(1, height)
    val buffer = IntArray(width * rowsPerRead)
    for (row in 0 until height step rowsPerRead) {
        val rows = minOf(rowsPerRead, height - row)
        pixels.read(x, y + row, width, rows, buffer)
        for (index in 0 until width * rows) {
            if (almostBlack(buffer[index])) continue
            val coordinate = if (vertical) row + index / width else index % width
            val inset = if (fromEnd) thickness - 1 - coordinate else coordinate
            if (inset < thickness - fringe) return 0
            verified = minOf(verified, inset)
        }
    }
    return verified
}
