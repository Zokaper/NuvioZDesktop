@file:OptIn(ExperimentalComposeUiApi::class)

package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput

/** The mouse thumb button, which is how a desktop user expects to go back. */
actual fun Modifier.platformPointerBackNavigation(onBack: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && !event.changes.any { it.isConsumed }) {
                    if (event.button == PointerButton.Back) {
                        event.changes.forEach { it.consume() }
                        onBack()
                    }
                }
            }
        }
    }

/**
 * Swallow Back and Forward over the player, so a thumb button cannot navigate out from under
 * playback. Consumed in the [PointerEventPass.Initial] pass, ahead of the navigation handler above.
 */
actual fun Modifier.platformPointerNavigationGuard(): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    if (event.button == PointerButton.Back || event.button == PointerButton.Forward) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }

/** Wheel scroll dismisses a hover preview. */
actual fun Modifier.platformPointerScrollDismiss(onScroll: () -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Scroll) { onScroll() }
