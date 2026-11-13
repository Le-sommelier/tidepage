package app.pdfa.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pdfa.app.R
import app.pdfa.app.settings.AppSettings
import app.pdfa.app.settings.SaveBehaviour
import app.pdfa.design.PdfaTheme

/**
 * Settings. [onChange] takes a transform so the caller applies it to the latest value.
 * Nothing here concerns accounts: there are none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onLicences: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_colour),
                hint = stringResource(R.string.settings_dynamic_colour_hint, stringResource(R.string.app_name)),
                checked = settings.dynamicColour,
                onCheckedChange = { value -> onChange { it.copy(dynamicColour = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_dim_pages),
                hint = stringResource(R.string.settings_dim_pages_hint),
                checked = settings.dimPages,
                onCheckedChange = { value -> onChange { it.copy(dimPages = value) } },
            )

            SectionHeader(stringResource(R.string.settings_saving))
            SaveChoiceRow(
                title = stringResource(R.string.settings_save_copy),
                hint = stringResource(R.string.settings_save_copy_hint),
                selected = settings.saveBehaviour == SaveBehaviour.SAVE_COPY,
                onSelect = { onChange { it.copy(saveBehaviour = SaveBehaviour.SAVE_COPY) } },
            )
            SaveChoiceRow(
                title = stringResource(R.string.settings_replace),
                hint = stringResource(R.string.settings_replace_hint),
                selected = settings.saveBehaviour == SaveBehaviour.REPLACE,
                onSelect = { onChange { it.copy(saveBehaviour = SaveBehaviour.REPLACE) } },
            )

            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                modifier = Modifier.clickable(role = Role.Button, onClick = onLicences),
                headlineContent = { Text(stringResource(R.string.menu_licences)) },
            )
            Text(
                text = stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun SaveChoiceRow(title: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.RadioButton, onClick = onSelect),
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    PdfaTheme {
        SettingsScreen(
            settings = AppSettings(),
            onChange = {},
            onBack = {},
            onLicences = {},
        )
    }
}
