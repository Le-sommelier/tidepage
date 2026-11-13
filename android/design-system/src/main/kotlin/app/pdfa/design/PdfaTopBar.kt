package app.pdfa.design

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * Top bar: a title, an optional back button and trailing actions. The back icon is a parameter
 * so callers can pass a mirrored or custom icon; the default is the auto-mirrored arrow.
 * Icon buttons keep the 48 dp minimum touch target from Material.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "Back",
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = backIcon, contentDescription = backContentDescription)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.background,
            scrolledContainerColor = colors.surface,
            navigationIconContentColor = colors.onBackground,
            titleContentColor = colors.onBackground,
            actionIconContentColor = colors.onBackground,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun PdfaTopBarPreview() {
    PdfaTheme(darkTheme = false) {
        PdfaTopBar(title = "Annual report.pdf", onBack = {}, actions = {})
    }
}

@Preview(showBackground = true, name = "Top bar, no back")
@Composable
private fun PdfaTopBarNoBackPreview() {
    PdfaTheme(darkTheme = true) {
        PdfaTopBar(title = "Recent files")
    }
}
