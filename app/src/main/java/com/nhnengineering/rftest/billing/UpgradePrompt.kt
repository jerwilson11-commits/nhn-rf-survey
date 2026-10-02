package com.nhnengineering.rftest.billing

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A one-bit, process-scoped request to show the upgrade screen.
 *
 * A gated control anywhere in the app calls [open] when a Free user taps a paid feature;
 * `MainActivity` observes [show] and overlays `PaywallScreen`. This mirrors the app's existing
 * singleton-state pattern ([com.nhnengineering.rftest.service.RecordingState],
 * [EntitlementRepository]) and avoids threading an `onUpgrade` callback through every screen that
 * has a paid button.
 */
object UpgradePrompt {
    val show = MutableStateFlow(false)
    fun open() { show.value = true }
    fun dismiss() { show.value = false }
}
