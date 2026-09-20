package com.nhnengineering.rftest.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nhnengineering.rftest.model.PreWalkCheck
import com.nhnengineering.rftest.session.SessionCsvWriter
import com.nhnengineering.rftest.wifi.WifiCollector

/**
 * Reads the real device state that [PreWalkCheck] judges.
 *
 * Every value is read fresh when the card composes rather than cached. These are exactly the
 * settings an operator changes in the minute before setting off, and a cached answer would tell
 * them about the state they were in when the app started.
 */
fun preWalkInputs(
    context: Context,
    hasGpsFix: Boolean,
    floorplanSelected: Boolean,
    simPresent: Boolean,
): PreWalkCheck.Inputs {
    fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    val lm = context.getSystemService(LocationManager::class.java)
    val servicesOn = runCatching {
        lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(LocationManager.FUSED_PROVIDER) == true
    }.getOrDefault(false)

    val throttleDisabled = runCatching {
        Settings.Global.getInt(context.contentResolver, WifiCollector.THROTTLE_SETTING, 1) == 0
    }.getOrDefault(false)

    val battery = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrNull()

    val writable = runCatching { SessionCsvWriter.sessionsDir(context).canWrite() }
        .getOrDefault(false)

    return PreWalkCheck.Inputs(
        locationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        locationServicesOn = servicesOn,
        hasGpsFix = hasGpsFix,
        floorplanSelected = floorplanSelected,
        scanThrottleDisabled = throttleDisabled,
        phoneStatePermission = granted(Manifest.permission.READ_PHONE_STATE),
        simPresent = simPresent,
        batteryPct = battery,
        storageWritable = writable,
    )
}

/**
 * Shown before a walk, hidden during one.
 *
 * Collapses to a single green line when everything passes, because a panel that always demands
 * reading gets skipped, and the one time it mattered would be the time it was skipped.
 */
@Composable
fun PreWalkCard(checks: List<PreWalkCheck.Check>) {
    val blocking = checks.filter { it.status == PreWalkCheck.Status.BLOCK }
    val warning = checks.filter { it.status == PreWalkCheck.Status.WARN }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Before you walk", style = MaterialTheme.typography.titleMedium)

            if (blocking.isEmpty() && warning.isEmpty()) {
                Text(
                    "All checks pass.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2E7D32),
                )
                return@Column
            }

            for (check in blocking + warning) {
                val colour = if (check.status == PreWalkCheck.Status.BLOCK) {
                    Color(0xFF8A1C1C)
                } else {
                    Color(0xFF8A5300)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colour)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (check.status == PreWalkCheck.Status.BLOCK) "STOP" else "CHECK",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White,
                        )
                        YieldingText(
                            text = check.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                        )
                    }
                    Text(
                        text = check.detail,
                        fontSize = 12.sp,
                        color = Color(0xFFFFE0B2),
                    )
                }
            }
        }
    }
}
