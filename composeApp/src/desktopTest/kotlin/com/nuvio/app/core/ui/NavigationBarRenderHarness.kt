package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.settings.NavBarStyle
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The floating navigation bar, rendered at the widths a phone actually gives it.
 *
 * ## Why this exists
 *
 * The bar shipped reading **")ownload"**. `NavItemLabel` put an unconstrained `Text` inside a `Box`
 * that set a height and no width, so on a 411dp phone with six tabs each label measured wider than
 * its ~55dp cell, spilled into its neighbours and was cut by their clip. Nothing in either
 * repository could have caught that: there is no test anywhere that draws this bar, and a label
 * that is cut still reports the right text to semantics. It was found by looking at a phone.
 *
 * So this file's job is to make the *fitting* question answerable without a phone. It renders the
 * real [NuvioNavigationBar] with the real six tabs, at the widths where the fit is tight, in both
 * label states.
 *
 * ## What it proves and does not prove
 *
 * It asserts every scene composes and renders without throwing, and - the one real assertion -
 * that [NuvioNavBarHeightState] reports a height that is **larger when the labels are shown**, and
 * that both figures are in the range a bar of this shape can actually occupy. That is the
 * regression guard for the reserve that used to be a hand-tuned `72.dp` literal.
 *
 * Whether a label is *cut* is still a question for the PNGs. Run it and look:
 *
 * ```
 * ./gradlew :composeApp:desktopTest --tests "*NavigationBarRenderHarness"
 * # then read composeApp/build/navbar-render/
 * ```
 *
 * The questions to ask of the output:
 *
 * 1. Is every one of the six labels **complete**? "Download" and "Settings" are the tight ones. A
 *    trailing ellipsis is an acceptable degradation; a bare stem like ")ownload" is the bug.
 * 2. At 320dp, have the labels dropped away entirely rather than turned into stems?
 * 3. Does the selected item's pill highlight sit behind its own icon and label, and nothing else?
 *
 * ⚠ **`desktopUiScale` is deliberately not passed.** The sibling harnesses pass the real desktop
 * scale because they are rendering a desktop window. These scenes are phones, and scaling them by
 * a desktop factor would render a layout no phone produces - which is the fault
 * `SocialRenderHarness` already carries a long warning about.
 */
class NavigationBarRenderHarness {

    private val outputDir = File("build/navbar-render")

    /** The six tabs the app actually ships, in order. "Download" and "Settings" are the tight ones. */
    private val tabs = listOf(
        "Home", "Search", "Library", "Download", "Social", "Settings",
    )

    /**
     * Widths where the fit is in question.
     *
     * 411 is the maintainer's S25 in portrait, which is where ")ownload" was photographed. 360 is
     * the common small Android phone and, measured, already too narrow for six labels. 320 is the
     * narrowest thing worth supporting. 891 is the same S25 in landscape.
     */
    private val widths = listOf(320, 360, 411, 891)

    @Test
    fun renderTheBarAtEveryPhoneWidth() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        for (width in widths) {
            for (style in listOf(NavBarStyle.EXPANDED, NavBarStyle.COMPACT)) {
                val name = "navbar-${width}-${style.name.lowercase()}"
                render(name, width, 140, failures) {
                    NavBarScene(style = style, selectedIndex = 4)
                }
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * The reserve screens leave for the bar must come from the bar.
     *
     * This is the regression guard for the `72.dp` literal that used to live in
     * `MainTabsDestination`: it was seven dp short of the expanded bar, which is how content ended
     * up behind it. The assertion is deliberately loose about the exact numbers - the bar's metrics
     * may legitimately be retuned - and strict about the two things that must stay true: a labelled
     * bar is taller than an unlabelled one, and neither is anywhere near zero.
     */
    @Test
    fun theBarReportsATallerReserveWhenItShowsLabels() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        val expanded = NuvioNavBarHeightState()
        val compact = NuvioNavBarHeightState()

        render("navbar-measure-expanded", 411, 140, failures) {
            NavBarScene(style = NavBarStyle.EXPANDED, selectedIndex = 4, heightState = expanded)
        }
        render("navbar-measure-compact", 411, 140, failures) {
            NavBarScene(style = NavBarStyle.COMPACT, selectedIndex = 4, heightState = compact)
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))

        val expandedHeight = expanded.overlayHeight.value
        val compactHeight = compact.overlayHeight.value

        if (expandedHeight <= compactHeight) {
            fail(
                "A bar showing labels must reserve more room than one hiding them, but got " +
                    "expanded=${expandedHeight}dp and compact=${compactHeight}dp. If these are " +
                    "equal the bar is no longer measuring itself and the reserve has gone back to " +
                    "being a constant.",
            )
        }
        if (compactHeight < 30f || expandedHeight > 140f) {
            fail(
                "The measured reserve left the range a bar of this shape can occupy: " +
                    "expanded=${expandedHeight}dp compact=${compactHeight}dp. Either the " +
                    "measurement is picking up the wrong node, or the navigation-bar inset is " +
                    "being counted twice.",
            )
        }
    }

    /**
     * Six labelled tabs have to survive a real phone, and have to give up on a very small one.
     *
     * ⚠ **This is an assertion about fitting, not about pixels, and it needs no eyes.** The bar
     * demotes every label when one of them cannot fit its cell, and a demoted bar is *shorter* -
     * so the height it reports says which decision it took. Asking the measurement is exact where
     * reading 10sp text off a PNG is not, and it is how the two real regressions in this area were
     * caught:
     *
     * - testing `hasVisualOverflow` rather than `didOverflowWidth` made every label report overflow
     *   at every width, because the label box is deliberately shorter than its own line height. The
     *   bar demoted to icons on a 891dp landscape phone with room to spare.
     * - the pill's `Radius.full` corners eat about 11dp at the labels' baseline, so with the row
     *   inset at `Space.s6` the outer two labels were drawn inside the curve and cut by it.
     * - each *cell* was clipped to `Radius.full` too, which on a ~58 x 62dp cell is nearly a
     *   circle; "Download" fitted its cell's width at 411dp and was still cut on both ends.
     * - the reserve is a per-layout maximum, and the demote is requested while the labelled layout
     *   is being measured - so it used to file the labelled height under the demoted layout and the
     *   reserve never came back down. That is why this test could pass while the bar was icons.
     *
     * If this fails, the bar has changed its mind about a width - decide which answer is right
     * before changing the numbers.
     */
    @Test
    fun labelsSurviveARealPhoneAndGiveUpOnAVerySmallOne() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        fun reservedAt(width: Int, style: NavBarStyle): Float {
            val state = NuvioNavBarHeightState()
            render("navbar-fit-$width-${style.name.lowercase()}", width, 140, failures) {
                NavBarScene(style = style, selectedIndex = 4, heightState = state)
            }
            return state.overlayHeight.value
        }

        // The height of a bar that is definitely showing labels, and one that definitely is not.
        val labelled = reservedAt(891, NavBarStyle.EXPANDED)
        val unlabelled = reservedAt(891, NavBarStyle.COMPACT)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))

        // 411dp is the maintainer's phone in portrait and the width ")ownload" was photographed at.
        // Six labels must still fit there, or the fix traded a cut label for no label.
        val onAPhone = reservedAt(411, NavBarStyle.EXPANDED)
        if (onAPhone != labelled) {
            fail(
                "At 411dp the bar gave up its labels (reserved ${onAPhone}dp, a labelled bar is " +
                    "${labelled}dp and an unlabelled one ${unlabelled}dp). Six labels have to fit " +
                    "a normal phone in portrait - demoting there is a worse bug than the clipping " +
                    "this replaced.",
            )
        }

        // 360dp and 320dp cannot hold six labels, and the bar must say so by dropping them all.
        // Measured, not assumed: "Download" is 58dp at `Type.labelXs`, so six cells need 348dp and
        // a 360dp window leaves 12dp for every margin and inset the pill has. Expecting labels at
        // 360 - as this harness once did - could only be satisfied by cutting one.
        //
        // This is also the half of the rule that went unasserted, and dead, through two committed
        // detectors, so it is asserted at both widths.
        for (narrow in listOf(360, 320)) {
            val reserved = reservedAt(narrow, NavBarStyle.EXPANDED)
            if (reserved != unlabelled) {
                fail(
                    "At ${narrow}dp the bar kept its labels (reserved ${reserved}dp, an unlabelled " +
                        "bar is ${unlabelled}dp). Six labels cannot fit there; either the demote is " +
                        "not firing and the bar is showing stems like \"Downl..\", or the reserve " +
                        "is still holding the labelled height it measured on the first frame.",
                )
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * A scroll-collapse and a scroll back must give the labels back.
     *
     * WARN **Found on a phone, after every static assertion above had passed.** While the labels
     * fade, the pill's horizontal padding animates towards its collapsed width, so the cells narrow
     * under labels that are still drawn. The fit check fired mid-animation at 411dp and the demote
     * latched at a width the bar never leaves: one scroll removed the labels until the app was
     * killed. A harness that only renders a bar at rest cannot see it, so this one drives the
     * animation frame by frame, the way the app does.
     */
    @Test
    fun aCollapseAndExpandGivesTheLabelsBack() {
        outputDir.mkdirs()
        val failures = mutableListOf<String>()

        val restingState = NuvioNavBarHeightState()
        render("navbar-cycle-rest", 411, 140, failures) {
            NavBarScene(style = NavBarStyle.EXPANDED, selectedIndex = 4, heightState = restingState)
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        val labelled = restingState.overlayHeight.value

        val heightState = NuvioNavBarHeightState()
        val scrollState = NuvioNavBarScrollState()
        runCatching {
            val scene = ImageComposeScene(width = 411, height = 140, density = Density(1f)) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false) {
                    NavBarScene(
                        style = NavBarStyle.ADAPTIVE,
                        selectedIndex = 4,
                        heightState = heightState,
                        scrollState = scrollState,
                    )
                }
            }
            try {
                var clock = 0L
                fun run(millis: Long) {
                    val end = clock + millis
                    while (clock < end) {
                        scene.render(clock * 1_000_000L)
                        clock += 16
                    }
                }
                run(100)
                scrollState.collapse()
                run(1_000)
                scrollState.expand()
                run(1_000)
                val image = scene.render(clock * 1_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData was null")
                File(outputDir, "navbar-cycle-after.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error -> failures += "navbar-cycle: ${error::class.simpleName}: ${error.message}" }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))

        val after = heightState.overlayHeight.value
        if (after != labelled) {
            fail(
                "After a collapse and an expand at 411dp the bar reserves ${after}dp, not the " +
                    "labelled ${labelled}dp: the labels did not come back. The fit check has fired " +
                    "on a mid-animation frame and latched the demote.",
            )
        }
    }

    @Composable
    private fun NavBarScene(
        style: NavBarStyle,
        selectedIndex: Int,
        heightState: NuvioNavBarHeightState? = null,
        scrollState: NuvioNavBarScrollState? = null,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // A little content behind it, so the pill's translucency reads as it does in the app
            // and it is obvious when something is hidden underneath.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Friends",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF1A1A1C)))
            }
            NuvioNavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                navBarStyle = style,
                heightState = heightState,
                scrollState = scrollState,
            ) {
                tabs.forEachIndexed { index, label ->
                    NavItem(
                        selected = index == selectedIndex,
                        onClick = {},
                        label = label,
                        content = {
                            Box(Modifier.height(28.dp).fillMaxWidth().background(Color.Transparent))
                        },
                    )
                }
            }
        }
    }

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        failures: MutableList<String>,
        content: @Composable () -> Unit,
    ) {
        runCatching {
            val scene = ImageComposeScene(
                width = widthDp,
                height = heightDp,
                density = Density(1f),
            ) {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, amoled = false) { content() }
            }
            try {
                // WARN **Render twice and keep the second frame.**
                //
                // The auto-demote reports overflow from `onTextLayout`, i.e. during layout, and the
                // recomposition that answers it lands on the *next* frame. A single `render(0L)`
                // therefore photographs the bar mid-decision and can never show a demoted label -
                // the harness would report the feature missing when it is merely one frame away.
                scene.render(0L)
                val image = scene.render(16L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encodeToData was null")
                File(outputDir, "$name.png").writeBytes(data.bytes)
            } finally {
                scene.close()
            }
        }.onFailure { error ->
            failures += "$name: ${error::class.simpleName}: ${error.message}"
        }
    }
}
