package app.pdfa.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pdfa.app.R
import app.pdfa.app.licences.LicenceDoc
import app.pdfa.app.licences.Licences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the screen shows: still loading, every text, or a failure. */
sealed interface LicenceState {
    data object Loading : LicenceState
    data class Ready(val texts: List<Pair<LicenceDoc, String>>) : LicenceState
    data object Failed : LicenceState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val state by produceState<LicenceState>(initialValue = LicenceState.Loading, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                LicenceState.Ready(Licences.documents.map { it to Licences.read(assets, it) })
            } catch (e: IOException) {
                LicenceState.Failed
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        when (val s = state) {
            LicenceState.Loading -> Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
                Text(stringResource(R.string.licences_loading), style = MaterialTheme.typography.bodyMedium)
            }

            LicenceState.Failed -> Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
                Text(stringResource(R.string.licences_unavailable), style = MaterialTheme.typography.bodyMedium)
            }

            is LicenceState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.licences_trademark),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                s.texts.forEach { (doc, text) ->
                    item(key = doc.assetName) {
                        Text(
                            text = stringResource(doc.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                        Text(text = text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
