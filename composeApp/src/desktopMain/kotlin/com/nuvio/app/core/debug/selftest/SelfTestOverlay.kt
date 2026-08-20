package com.nuvio.app.core.debug.selftest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.debug.isDebugBuild

/**
 * A small status card in the corner while a self-test is running.
 *
 * ⚠ **Draws nothing interactive and consumes no pointer input, on purpose.** The suite synthesises
 * real mouse clicks at the centre of the window and photographs what is underneath; anything that
 * covered the app or swallowed input would corrupt both. That is the inverse of the usual rule for
 * a full-screen surface here - which must consume input, and has twice shipped not doing so - and
 * the difference is that this is not full-screen and is not asking to be touched.
 *
 * Debug builds only. On any other build [isDebugBuild] is false and this composes nothing.
 */
@Composable
internal fun SelfTestOverlay(modifier: Modifier = Modifier) {
    if (!isDebugBuild) return
    val state = DesktopSelfTest.state
    if (!state.isRunning && state.summary == null) return
    // Never appear in the evidence. See `SelfTestUiState.suppressedForCapture`.
    if (state.suppressedForCapture) return

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6101010))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = if (state.isRunning) "Self-test running" else "Self-test finished",
                color = Color.White,
                fontSize = 13.sp,
            )
            state.summary?.let { summary ->
                Text(text = summary, color = Color(0xFF9AD29A), fontSize = 12.sp)
            }
            state.recentSteps.forEach { step ->
                Text(
                    text = step,
                    color = Color(0xFFBFBFBF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            state.runDirectory?.let { directory ->
                Text(
                    text = directory.toString(),
                    color = Color(0xFF7F7F7F),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
