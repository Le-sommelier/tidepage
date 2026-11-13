package app.pdfa.annotate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// The core icon set has none of these. Material paths, 24 x 24.

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

internal val HighlighterIcon: ImageVector by lazy {
    icon(
        "Highlighter",
        "M6,14l3,3v5h6v-5l3,-3V9H6V14zM11,2h2v3h-2V2zM3.5,5.88l1.41,-1.41l2.12,2.12L5.62,8L3.5,5.88zM16.96,6.59l2.12,-2.12l1.41,1.41L18.38,8L16.96,6.59z",
    )
}

internal val NoteIcon: ImageVector by lazy {
    icon(
        "Note",
        "M19,3H4.99C3.89,3 3,3.9 3,5l0.01,14c0,1.1 0.89,2 1.99,2h10l6,-6V5C21,3.9 20.1,3 19,3zM7,8h10v2H7V8zM12,14H7v-2h5V14zM14,19.5V14h5.5L14,19.5z",
    )
}

internal val UndoIcon: ImageVector by lazy {
    icon("Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
}
