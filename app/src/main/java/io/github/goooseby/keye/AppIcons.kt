package io.github.goooseby.keye

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Original outline glyphs drawn on a shared 24-unit grid. */
private val glyphs = mapOf(
    "code" to "M8 6l-6 6 6 6 M16 6l6 6-6 6 M14 3l-4 18",
    "expand" to "M5 9l7 7 7-7",
    "brightness" to "M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M5 19l1.5-1.5 M17.5 6.5L19 5",
    "previous" to "M15 5l-7 7 7 7",
    "first" to "M5 5v14 M19 5l-8 7 8 7",
    "last" to "M19 5v14 M5 5l8 7-8 7",
    "fit" to "M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5 M8 8h8v8H8Z",
    "tools" to "M4 6h16 M4 12h16 M4 18h16 M8 3v6 M16 9v6 M10 15v6",
    "live" to "M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4 M7 7a7 7 0 0 0 0 10 M17 7a7 7 0 0 1 0 10 M4 4a11 11 0 0 0 0 16 M20 4a11 11 0 0 1 0 16",
    "pause" to "M8 5v14 M16 5v14",
    "library" to "M4 3h6v18H4z M14 3h6v18h-6z M6.5 7h1 M16.5 7h1 M6.5 17h1 M16.5 17h1",
    "download" to "M12 3v12 M7 10l5 5 5-5 M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4",
    "share" to "M8 12l8-5 M8 12l8 5 M5 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6 M19 2a3 3 0 1 0 0 6a3 3 0 0 0 0-6 M19 16a3 3 0 1 0 0 6a3 3 0 0 0 0-6",
    "fullscreen" to "M9 3H3v6 M15 3h6v6 M3 15v6h6 M21 15v6h-6",
    "grid" to "M3 3h7v7H3Z M14 3h7v7h-7Z M3 14h7v7H3Z M14 14h7v7h-7Z",
    "bookmark" to "M6 3h12v18l-6-4-6 4Z",
    "export" to "M12 16V4 M7 9l5-5 5 5 M4 15v5a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-5",
    "tasks" to "M9 5h12 M9 12h12 M9 19h12 M3 5l1 1 2-2 M3 12l1 1 2-2 M3 19h3",
    "settings" to "M9.6 3l-.5 2.3-1.5.9-2.3-.7-2.4 4.1 1.8 1.6v1.7l-1.8 1.6 2.4 4.1 2.3-.7 1.5.9.5 2.2h4.8l.5-2.2 1.5-.9 2.3.7 2.4-4.1-1.8-1.6v-1.7l1.8-1.6-2.4-4.1-2.3.7-1.5-.9-.5-2.3z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
    "back" to "M20 12H4 M10 6l-6 6 6 6",
    "next" to "M9 5l7 7-7 7",
    "more" to "M12 4v.1 M12 12v.1 M12 20v.1",
    "add" to "M12 4v16 M4 12h16",
    "folder" to "M3 7a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z",
    "check" to "M5 12l4 4L19 6",
    "close" to "M6 6l12 12 M18 6L6 18",
    "search" to "M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0 M15 15l6 6",
    "refresh" to "M20 8a8 8 0 1 0 0 8 M20 3v5h-5",
    "document" to "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6 M8 13h8 M8 17h5",
    "trash" to "M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7",
    "undo" to "M9 5l-6 5 6 5 M3 10h10a6 6 0 0 1 0 12h-2",
    "image" to "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M3 17l6-6 4 4 3-3 5 5 M16 7h.1",
    "calendar" to "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M7 3v4 M17 3v4 M3 11h18 M7 15h2 M13 15h2",
    "school" to "M3 9l9-6 9 6 M5 9v12h14V9 M9 21v-6h6v6 M9 10h.1 M15 10h.1",
    "clock" to "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 7v5l3 2",
    "error" to "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 7v6 M12 17v.1",
    "info" to "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 11v6 M12 7v.1",
    "edit" to "M4 20l1-5L16 4a2.1 2.1 0 0 1 3 3L8 18z M14 6l4 4",
    "move" to "M3 7a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z M8 14h8 M13 11l3 3-3 3",
    "select" to "M8 3H4a1 1 0 0 0-1 1v4 M16 3h4a1 1 0 0 1 1 1v4 M3 16v4a1 1 0 0 0 1 1h4 M16 21h4a1 1 0 0 0 1-1v-4 M8 12l3 3 5-6",
    "zoom" to "M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0 M14.5 14.5L21 21 M7 10h6 M10 7v6",
    "logout" to "M10 4H5v16h5 M10 12h11 M17 8l4 4-4 4",
    "storage" to "M4 4h16v6H4z M4 14h16v6H4z M7 7h.1 M7 17h.1 M12 7h5 M12 17h5"
)
@Composable
fun AppIcon(name: String, description: String? = null, modifier: Modifier = Modifier) {
    val vector = remember(name) {
        ImageVector.Builder(name=name, defaultWidth=24.dp, defaultHeight=24.dp, viewportWidth=24f, viewportHeight=24f).apply {
            addPath(pathData=PathParser().parsePathString(glyphs[name] ?: glyphs.getValue("document")).toNodes(),
                stroke=SolidColor(Color.Black), strokeLineWidth=1.7f, strokeLineCap=StrokeCap.Round,
                strokeLineJoin=StrokeJoin.Round, fill=null)
        }.build()
    }
    Icon(vector, description, modifier.size(24.dp))
}
