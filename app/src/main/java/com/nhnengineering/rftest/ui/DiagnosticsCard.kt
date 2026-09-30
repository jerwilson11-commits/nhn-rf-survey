package com.nhnengineering.rftest.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * One button: export a support bundle and hand it to whatever the phone's share sheet offers
 * (email, a support ticket form, a messaging app).
 *
 * Lives outside the paywalled tiers deliberately -- see [com.nhnengineering.rftest.support.DiagnosticsExporter]'s
 * doc for why a non-rooted, field-tier customer hitting a bug is exactly who this also has to work
 * for.
 */
@Composable
fun DiagnosticsCard(
    running: Boolean,
    resultMessage: String?,
    onExport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bundles the device model, root status, and a read-only check of what the modem " +
                    "reported -- nothing is written to the radio. Attach it to a support message " +
                    "when something doesn't work as expected.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onExport, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Collecting…" else "Export diagnostics")
            }
            resultMessage?.let {
                HorizontalDivider()
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun exportFile(context: Context, filePrefix: String): File {
    val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
    return File(dir, "${filePrefix}_${System.currentTimeMillis()}.txt")
}

/** Writes [text] to a fresh file under the app's own exports directory and hands it to the OS
 *  share sheet. Mirrors `SessionsScreen.kt`'s identical `share()` -- both need the same
 *  `FileProvider` authority and grant, and duplicating it here keeps this card self-contained.
 *  [filePrefix] names the export (e.g. "diagnostics", "signaling_capture") -- this helper is not
 *  specific to the diagnostics bundle, any plain-text export in the app can share it. */
fun shareDiagnosticsText(context: Context, text: String, filePrefix: String = "diagnostics"): File {
    val file = exportFile(context, filePrefix)
    file.writeText(text)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    return file
}
