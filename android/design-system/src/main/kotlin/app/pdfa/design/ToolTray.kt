package app.pdfa.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** One slot in the bottom tool tray. The label is always visible; it is also the accessible name. */
data class ToolTrayItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val onClick: () -> Unit,
    val contentDescription: String = label,
)

/** Bottom tool tray with always-visible labels. Each item is at least 48 dp. */
@Composable
fun ToolTray(items: List<ToolTrayItem>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(PdfaSize.ToolTrayHeight),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PdfaSpacing.S2)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                ToolTrayButton(item = item, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTrayButton(item: ToolTrayItem, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = if (item.selected) colors.primary else colors.onSurfaceVariant
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = PdfaSpacing.MinTouchTarget, minHeight = PdfaSpacing.MinTouchTarget)
            .selectable(
                selected = item.selected,
                onClick = item.onClick,
                role = Role.Tab,
            )
            .semantics { contentDescription = item.contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (item.selected) colors.primaryContainer else Color.Transparent,
                    shape = PdfaFullShape,
                )
                .padding(horizontal = PdfaSpacing.S4, vertical = PdfaSpacing.S1),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (item.selected) colors.onPrimaryContainer else tint,
                modifier = Modifier.size(PdfaSize.IconDefault),
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ToolTrayPreview() {
    PdfaTheme(darkTheme = false) {
        ToolTray(
            items = listOf(
                ToolTrayItem("Organize", Icons.Filled.Edit, selected = true, onClick = {}),
                ToolTrayItem("Search", Icons.Filled.Search, onClick = {}),
                ToolTrayItem("Add", Icons.Filled.Add, onClick = {}),
                ToolTrayItem("Delete", Icons.Filled.Delete, onClick = {}),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, name = "Tool tray dark")
@Composable
private fun ToolTrayDarkPreview() {
    PdfaTheme(darkTheme = true) {
        ToolTray(
            items = listOf(
                ToolTrayItem("Organize", Icons.Filled.Edit, selected = true, onClick = {}),
                ToolTrayItem("Search", Icons.Filled.Search, onClick = {}),
                ToolTrayItem("Add", Icons.Filled.Add, onClick = {}),
            ),
        )
    }
}
