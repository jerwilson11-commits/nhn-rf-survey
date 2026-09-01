package com.nhnengineering.rftest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nhnengineering.rftest.ui.SessionsScreen
import com.nhnengineering.rftest.ui.WifiDashboard
import com.nhnengineering.rftest.ui.theme.RFTestAppTheme

/**
 * Permissions this app cannot function without.
 *
 * ACCESS_FINE_LOCATION is the important one and is not optional: without it Android redacts BSSID
 * to 02:00:00:00:00:00 and returns an empty scan list. The same rule governs cell identity in
 * Phase 5 — on both radios, location permission is what unlocks identity.
 */
private val REQUIRED_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

/**
 * Requested alongside the required set, but deliberately NOT gated on.
 *
 * Without POST_NOTIFICATIONS the foreground service still runs and still records — only its
 * notification is suppressed, costing the visible indicator and the Stop action. That is a
 * degraded experience, not a broken app, so blocking every feature behind it would be wrong.
 */
private val OPTIONAL_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

private fun hasAllPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private enum class Tab(val label: String) {
    LIVE("Live"),
    SESSIONS("Sessions"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RFTestAppTheme {
                RfTestApp()
            }
        }
    }
}

@Composable
private fun RfTestApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-query rather than trusting the result map. On Android 12+ the user can pick
        // "Approximate" instead of "Precise", which grants ACCESS_COARSE_LOCATION and denies
        // ACCESS_FINE_LOCATION — a partial grant that otherwise looks like success.
        granted = hasAllPermissions(context)
    }

    val optionalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* nothing is gated on these */ }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS)
    }

    // Separate pass for the optional set. Without this, a user who granted location on an earlier
    // build never gets asked for notifications at all — the combined request above only fires when
    // the required permissions are missing.
    var askedOptional by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (!granted || askedOptional) return@LaunchedEffect
        askedOptional = true
        val missing = OPTIONAL_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) optionalLauncher.launch(missing.toTypedArray())
    }

    var tab by remember { mutableStateOf(Tab.LIVE) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (granted) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {},
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            !granted -> PermissionGate(
                modifier = Modifier.padding(innerPadding),
                onRequest = { launcher.launch(REQUIRED_PERMISSIONS + OPTIONAL_PERMISSIONS) },
            )
            // Safe to switch tabs mid-session since Phase 6: the recording lives in
            // RecordingService, not in this composition.
            tab == Tab.LIVE -> WifiDashboard(modifier = Modifier.padding(innerPadding))
            else -> SessionsScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun PermissionGate(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Precise location required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Android gates radio identity behind location permission. Without Precise " +
                "location, BSSIDs are redacted and scan results come back empty.\n\n" +
                "Choose \"Precise\", not \"Approximate\".",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Grant permission") }
        Text(
            text = "If the dialog stops appearing, permission was permanently denied — enable it " +
                "in Settings › Apps › RF Test App › Permissions › Location.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
