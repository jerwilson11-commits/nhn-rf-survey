package com.nhnengineering.rftest.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.android.billingclient.api.ProductDetails
import com.nhnengineering.rftest.billing.EntitlementRepository
import com.nhnengineering.rftest.billing.FIELD_PRODUCT_ID
import com.nhnengineering.rftest.billing.PRO_PRODUCT_ID
import com.nhnengineering.rftest.billing.PurchaseFlow
import com.nhnengineering.rftest.modem.ProCapability
import com.nhnengineering.rftest.modem.ProModem
import com.nhnengineering.rftest.modem.ProModemCapability

/**
 * The upgrade screen, shown as an overlay when a Free user taps a paid feature (via
 * [com.nhnengineering.rftest.billing.UpgradePrompt], observed in `MainActivity`). The app is fully
 * usable at the Free tier, so this is a "here's what each paid tier adds" interstitial with a way
 * back, not a wall blocking the whole app.
 *
 * Prices and trial terms are never hardcoded here -- they come from [ProductDetails], fetched
 * fresh from Play Console each time this screen is shown, so a price change in Console needs no
 * app update to take effect.
 */
@Composable
fun PaywallScreen(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    var products by remember { mutableStateOf<Map<String, ProductDetails>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    // Device capability for Pro's root-gated modem tools -- resolved separately (probes su) so it
    // never delays the product/price load, and so the Pro card can caveat honestly.
    var cap by remember { mutableStateOf<ProCapability?>(null) }

    LaunchedEffect(Unit) {
        products = EntitlementRepository.queryProductDetails()
        loaded = true
    }
    LaunchedEffect(Unit) { cap = ProModem.capability() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Upgrade Site Survey Pro",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = "You're on the free plan: live measurement, recording, raw-CSV export, " +
                    "speed tests and video/voice QoE. Upgrade for the paid features below. " +
                    "Cancel anytime.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!loaded) {
            item {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
        } else {
            item {
                TierCard(
                    title = "Field",
                    productDetails = products[FIELD_PRODUCT_ID],
                    features = listOf(
                        "PDF client acceptance report",
                        "KML, GeoJSON, GeoPackage and iBwave export",
                        "Floorplan survey mode",
                        "Cell lock watch",
                        "Automation (looped/scripted testing) and threshold alarms",
                    ),
                )
            }
            item {
                // Honest, device-specific caveat for Pro's root-gated modem tools -- shown before
                // any purchase so no one buys Pro expecting tools their device can't run.
                val proCaveat: Pair<String, Boolean>? = when (cap?.state) {
                    ProModemCapability.WRONG_CHIPSET ->
                        ("⚠ Pro's modem tools need a Qualcomm modem — this device is " +
                            "${cap?.vendorLabel}, so they can't run here even with Pro. Field features " +
                            "and the no-root capture import still work fully.") to true
                    ProModemCapability.NEEDS_ROOT ->
                        ("⚠ Pro's modem tools need root access, which isn't enabled on this device. " +
                            "You can still subscribe, but they stay locked until it's rooted. Field " +
                            "features need no root.") to true
                    ProModemCapability.CAPABLE ->
                        ("✓ This device supports Pro's modem tools.") to false
                    else -> null
                }
                TierCard(
                    title = "Pro",
                    productDetails = products[PRO_PRODUCT_ID],
                    features = listOf(
                        "Everything in Field",
                        "Band lock and technology lock (rooted, Qualcomm devices)",
                        "VoNR control (rooted, Qualcomm devices)",
                        "Live SIB1/TDD decode (rooted, Qualcomm devices)",
                        "NR neighbour reads over QMI (rooted, Qualcomm devices)",
                    ),
                    caveatText = proCaveat?.first,
                    caveatWarning = proCaveat?.second ?: false,
                )
            }
        }
        item {
            Text(
                text = "Cancel anytime from Play Store > Subscriptions.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onClose != null) {
            item {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep using the free plan")
                }
            }
        }
    }
}

@Composable
private fun TierCard(
    title: String,
    productDetails: ProductDetails?,
    features: List<String>,
    caveatText: String? = null,
    caveatWarning: Boolean = false,
) {
    val offer = productDetails?.subscriptionOfferDetails?.firstOrNull()
    val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
    val activity = LocalContext.current as? Activity

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = price ?: "Price unavailable",
                style = MaterialTheme.typography.titleMedium,
            )
            features.forEach { feature ->
                Text("• $feature", style = MaterialTheme.typography.bodyMedium)
            }
            caveatText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (caveatWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Button(
                onClick = {
                    if (activity != null && productDetails != null && offer != null) {
                        PurchaseFlow.launch(activity, productDetails, offer.offerToken)
                    }
                },
                enabled = activity != null && productDetails != null && offer != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Subscribe to $title")
            }
        }
    }
}
