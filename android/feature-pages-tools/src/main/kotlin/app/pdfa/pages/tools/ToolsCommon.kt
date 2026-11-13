package app.pdfa.pages.tools

import app.pdfa.feature.pages.tools.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.pdfa.design.PdfaSpacing

@Composable
internal fun inputErrorText(error: InputError, pageCount: Int?): String = when (error) {
    InputError.EMPTY -> stringResource(R.string.input_empty)
    InputError.SYNTAX -> stringResource(R.string.input_syntax)
    InputError.ZERO -> stringResource(R.string.input_zero)
    InputError.REVERSED -> stringResource(R.string.input_reversed)
    InputError.OUT_OF_BOUNDS -> stringResource(R.string.input_out_of_bounds, pageCount ?: 0)
    InputError.OVERLAP -> stringResource(R.string.input_overlap)
    InputError.TOO_MANY -> stringResource(R.string.input_too_many)
    InputError.N_TOO_SMALL -> stringResource(R.string.input_n_small)
    InputError.N_TOO_BIG -> stringResource(R.string.input_n_big, pageCount ?: 0)
    InputError.FIRST_PAGE -> stringResource(R.string.input_first_page)
    InputError.DUPLICATE -> stringResource(R.string.input_duplicate)
    InputError.TOO_MANY_PARTS -> stringResource(R.string.input_too_many_parts)
}

@Composable
internal fun problemText(problem: ToolProblem, detail: String?): String {
    val base = when (problem) {
        ToolProblem.TOO_LARGE -> stringResource(R.string.problem_too_large)
        ToolProblem.NOT_PERMITTED -> stringResource(R.string.problem_not_permitted)
        ToolProblem.TIMEOUT -> stringResource(R.string.problem_timeout)
        ToolProblem.ENGINE_DIED -> stringResource(R.string.problem_engine_died)
        ToolProblem.DAMAGED -> stringResource(R.string.problem_damaged)
        ToolProblem.FAILED -> stringResource(R.string.problem_failed)
    }
    return if (problem == ToolProblem.FAILED && !detail.isNullOrBlank()) "$base ($detail)" else base
}

/** A dismissable message card: errors and notices. */
@Composable
internal fun MessageCard(text: String, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier, error: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else colors.surfaceContainerHigh,
            contentColor = if (error) colors.onErrorContainer else colors.onSurface,
        ),
    ) {
        Row(
            Modifier.padding(start = PdfaSpacing.S4, top = PdfaSpacing.S2, bottom = PdfaSpacing.S2, end = PdfaSpacing.S2),
            horizontalArrangement = Arrangement.spacedBy(PdfaSpacing.S2),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = PdfaSpacing.S2))
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.tools_dismiss)) }
        }
    }
}
