package com.music.bitchord.ui.easteregg

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * The Home logo under the Vice colours: an eighties neon sign. The waves are
 * the lit tube — pale, nearly white pink, as a tube reads up close — inside
 * two layers of hot pink glow, a tight one and a wide one. When it comes on
 * it flickers into life the way a sign does, then holds with a faint hum in
 * its glow. Still, and simply lit, under Reduce animation.
 */
@Composable
internal fun ViceNeonLogo(modifier: Modifier = Modifier) {
    // Each wave is a tube of its own, on its own current: the mark's three
    // sub-paths, drawn separately, each fitted the same way as the whole.
    val waves = NEON_WAVE_PATHS.map { rememberVectorPainter(remember(it) { waveVector(it) }) }
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    val start = if (reduceAnimation || NeonSign.lit) 1f else 0f
    val power = remember { List(waves.size) { Animatable(start) } }
    LaunchedEffect(reduceAnimation) {
        if (reduceAnimation) {
            power.forEach { it.snapTo(1f) }
            return@LaunchedEffect
        }
        // The switch-on: lit, a stutter, out, lit again, a last blink, then
        // steady. Once a session: coming back to Home from another tab finds
        // the sign already lit, as a sign left on would be.
        if (!NeonSign.lit) {
            for ((level, holdMs) in NEON_SWITCH_ON) {
                power.forEach { it.snapTo(level) }
                delay(holdMs)
            }
            power.forEach { it.snapTo(1f) }
            NeonSign.lit = true
        }
        // Then, as an old sign does, a loose contact now and again: every few
        // seconds one wave stutters — the top, the middle or the bottom — or
        // now and then all three at once.
        while (true) {
            delay(NEON_FAULT_EVERY_MS + Random.nextLong(-NEON_FAULT_JITTER_MS, NEON_FAULT_JITTER_MS))
            val pick = Random.nextInt(waves.size + 1)
            val faulty = if (pick == waves.size) power else listOf(power[pick])
            val pattern = NEON_FAULTS.random()
            for ((level, holdMs) in pattern) {
                faulty.forEach { it.snapTo(level) }
                delay(holdMs)
            }
            faulty.forEach { it.snapTo(1f) }
        }
    }
    // The hum: the glow breathing a few percent, too slow and slight to read as
    // motion, enough to read as current.
    val hum = if (reduceAnimation) {
        null
    } else {
        rememberInfiniteTransition(label = "neonHum").animateFloat(
            initialValue = 0.88f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "neonHumLevel",
        )
    }

    // By day a pale tube would vanish into the pastels: the tube itself goes
    // hot pink, and the glow around it softer.
    val day = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val tubeLit = if (day) NEON_DAY_TUBE else NEON_TUBE
    val tubeUnlit = if (day) NEON_DAY_TUBE_OFF else NEON_TUBE_OFF
    val glowScale = if (day) NEON_DAY_GLOW_SCALE else 1f
    Box(modifier) {
        waves.forEachIndexed { index, wave ->
            val level = power[index]
            // The wide glow, spilling well past the tube.
            NeonLayer(wave, NEON_GLOW, blur = 9.dp) { glowScale * NEON_WIDE_GLOW_ALPHA * level.value * (hum?.value ?: 1f) }
            // The tight glow hugging it.
            NeonLayer(wave, NEON_GLOW, blur = 2.5.dp) { glowScale * NEON_NEAR_GLOW_ALPHA * level.value * (hum?.value ?: 1f) }
            // The tube itself: unlit, a dim pink glass; lit, nearly white (hot pink by day).
            NeonLayer(wave, tubeUnlit, blur = null) { 1f }
            NeonLayer(wave, tubeLit, blur = null) { level.value }
        }
    }
}

/** One wave of the mark as a vector of its own, on the mark's whole viewport so it lands where it does in the mark. */
private fun waveVector(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 28.dp,
        defaultHeight = 18.dp,
        viewportWidth = 730f,
        viewportHeight = 484f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White),
    ).build()

/**
 * ic_logo's three waves, bottom to top: its one path split at each sub-path,
 * so each can be lit on its own.
 */
private val NEON_WAVE_PATHS = listOf(
    "M6.73729,316.937C20.201,294.159 49.6986,286.649 72.4033,300.174H72.4277C237.603,398.282 510.121,407.302 667.823,359.439C693.147,351.759 719.927,366.055 727.602,391.378C735.276,416.715 720.992,443.477 695.649,451.176C514.618,506.132 213.674,495.514 23.5058,382.622C0.678772,369.103 -6.78757,339.685 6.73729,316.937Z",
    "M77.8135,154.865C89.38,136.046 113.982,130.11 132.77,141.676C270.528,226.369 480.519,250.891 643.46,201.43C664.592,195.047 686.911,206.957 693.324,228.052C699.689,249.184 687.774,271.46 666.679,277.886C480.555,334.36 249.169,307.004 90.9707,209.79C72.1827,198.224 66.247,173.628 77.8135,154.865Z",
    "M140.542,15.3012C149.722,0.246392 169.428,-4.52761 184.482,4.71335C304.811,78.2128 456.29,94.8593 634.684,54.101C651.874,50.1843 669.01,60.9551 672.927,78.1518C676.862,95.3486 666.134,112.484 648.9,116.401C453.677,161.021 286.219,141.799 151.129,59.2417C136.074,50.0006 131.301,30.3561 140.542,15.3012Z",
)

/** About how often a loose contact makes one of the waves stutter. */
private const val NEON_FAULT_EVERY_MS = 3000L

/** How far either side of that it lands, so it never ticks like a clock. */
private const val NEON_FAULT_JITTER_MS = 700L

/** A few ways a tube stutters, as (light level, ms held); one is picked each time. */
private val NEON_FAULTS = listOf(
    listOf(0.15f to 60L, 1f to 70L, 0.3f to 50L),
    listOf(0f to 90L, 1f to 40L, 0f to 60L),
    listOf(0.4f to 40L, 1f to 40L, 0.1f to 120L, 0.7f to 50L),
    listOf(0.2f to 140L),
)

/** One layer of the sign: the mark in [colour], optionally blurred into a glow, at [alpha]. */
@Composable
private fun NeonLayer(painter: Painter, colour: Color, blur: Dp?, alpha: () -> Float) {
    // The light level goes into the colour, read as the layer draws, rather
    // than onto a layer of its own: a faded layer is drawn offscreen at the
    // mark's size, and would cut the glow off square at the mark's edges.
    Box(
        Modifier
            .fillMaxSize()
            .then(if (blur != null) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier)
            .drawBehind { drawFittedTinted(painter, colour.copy(alpha = colour.alpha * alpha().coerceIn(0f, 1f))) },
    )
}

/** The mark fitted inside the box at its own proportions and centred, in [colour]. */
private fun DrawScope.drawFittedTinted(painter: Painter, colour: Color) {
    val intrinsic = painter.intrinsicSize
    val filter = ColorFilter.tint(colour)
    if (!intrinsic.isSpecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        with(painter) { draw(size, colorFilter = filter) }
        return
    }
    val scale = min(size.width / intrinsic.width, size.height / intrinsic.height)
    val fitted = Size(intrinsic.width * scale, intrinsic.height * scale)
    translate(left = (size.width - fitted.width) / 2f, top = (size.height - fitted.height) / 2f) {
        with(painter) { draw(fitted, colorFilter = filter) }
    }
}

/** Whether the sign has already switched on in this run of the app. */
private object NeonSign {
    var lit = false
}

/** Hot pink: the glow around the tube. */
private val NEON_GLOW = Color(0xFFFF2FA0)

/** The lit tube, pale enough to read as light rather than paint. */
private val NEON_TUBE = Color(0xFFFFD6EE)

/** The tube with no current in it: a dim pink glass, so the sign is there before it lights. */
private val NEON_TUBE_OFF = Color(0xFF6E2A55)

/** By day: the lit tube in hot pink, so it reads on the pastels. */
private val NEON_DAY_TUBE = Color(0xFFE5268C)

/** By day, unlit: a pale pink glass. */
private val NEON_DAY_TUBE_OFF = Color(0xFFF0C6DD)

/** By day the glow is softer: on a light page a full glow turns to a pink smudge. */
private const val NEON_DAY_GLOW_SCALE = 0.6f

private const val NEON_WIDE_GLOW_ALPHA = 0.85f
private const val NEON_NEAR_GLOW_ALPHA = 1f

/** The switch-on, as (light level, how long it holds in ms). */
private val NEON_SWITCH_ON = listOf(
    0f to 180L,
    1f to 70L,
    0.15f to 90L,
    1f to 50L,
    0f to 160L,
    0.8f to 60L,
    0.3f to 70L,
    1f to 0L,
)
