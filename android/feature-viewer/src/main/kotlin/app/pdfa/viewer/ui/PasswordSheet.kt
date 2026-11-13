package app.pdfa.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pdfa.design.PdfaBottomSheet
import app.pdfa.feature.viewer.R
import app.pdfa.viewer.ViewerState

/**
 * Password prompt. The typed text lives in plain `remember` (never `rememberSaveable`), is turned
 * into a CharArray on submit and cleared at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordSheet(
    state: ViewerState.PasswordRequired,
    onSubmit: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    PdfaBottomSheet(onDismissRequest = onCancel) {
        // The sheet is its own window: keep it out of screenshots and the recents thumbnail.
        SecureSheetWindowEffect()
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun submit() {
            if (text.isEmpty() || state.submitting) return
            val chars = text.toCharArray()
            text = ""
            onSubmit(chars)
        }
        Text(stringResource(R.string.viewer_password_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.viewer_password_label)) },
            singleLine = true,
            isError = state.lastAttemptWrong,
            enabled = !state.submitting,
            supportingText = if (state.lastAttemptWrong) {
                { Text(stringResource(R.string.viewer_password_wrong, state.attemptsLeft)) }
            } else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrect = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.viewer_cancel)) }
            Button(onClick = { submit() }, enabled = text.isNotEmpty() && !state.submitting) { Text(stringResource(R.string.viewer_password_open)) }
        }
    }
}
