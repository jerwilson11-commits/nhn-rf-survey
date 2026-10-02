package com.nhnengineering.rftest.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the full-screen floorplan "walk mode" is on.
 *
 * A process-scoped singleton, mirroring [com.nhnengineering.rftest.billing.UpgradePrompt] and
 * [com.nhnengineering.rftest.service.RecordingState]: both `MainActivity` (which hides its bottom
 * navigation while walking) and `FloorplanScreen` (which swaps its scrolling layout for the
 * immersive canvas) read the same flag, and there is no DI framework to thread it through.
 *
 * Walk mode exists because tapping a position on a floorplan boxed into a scrolling column of cards
 * is hard on a phone -- the reason field testers reach for tablets. Full screen plus the canvas's
 * existing pinch-zoom gives a phone roughly the tappable area of a tablet.
 */
object WalkMode {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun enter() { _active.value = true }
    fun exit() { _active.value = false }
}
