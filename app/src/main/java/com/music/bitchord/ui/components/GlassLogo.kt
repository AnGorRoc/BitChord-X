package com.music.bitchord.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.blur
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.effects.effect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The BitChord mark at the head of Home, made of the same material as the
 * floating controls around it rather than printed in ink.
 *
 * With liquid glass on, the mark is a piece of glass: the page behind it is
 * blurred, saturated and bent along the mark's own outline — not along a
 * rounded rectangle, which is all the backdrop's own lens can follow — with
 * a rim catching the light, and the now-playing tint laid over it as on the
 * bars. With it off, the mark is cut out of the frosted material the top
 * controls use: the page blurred under the bar's own container, which is the
 * playing record's shade while the tint is on.
 *
 * Either way it is one shape masking a surface: everything is drawn into an
 * offscreen layer and the mark's alpha keeps only what falls inside it.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlassLogo(
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    // Under the easter egg's colours the mark is a neon sign instead.
    val viceTheme by AppSettings.viceTheme.collectAsStateWithLifecycle()
    val viceUnlocked by AppSettings.viceUnlocked.collectAsStateWithLifecycle()
    if (viceTheme && viceUnlocked) {
        com.music.bitchord.ui.easteregg.ViceNeonLogo(modifier)
        return
    }
    val painter = painterResource(R.drawable.ic_logo)
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported() && !reduceDynamicBlur
    val container = chromeSurface()
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    val surface: Modifier = when {
        useGlass -> {
            val density = LocalDensity.current
            val backdrop = LocalAppBackdrop.current
            val heightField = remember(painter) { LogoHeightField(painter) }
            val refraction = remember { LogoRefraction() }
            val themeTint = if (darkTheme) Color(0xFF121212) else Color(0xFFFAFAFA)
            val tint = chromeGlassTint() ?: themeTint.copy(alpha = LOGO_SURFACE_OPACITY)
            val blurPx = with(density) { LOGO_BLUR.toPx() }
            val bendPx = with(density) { LOGO_BEND.toPx() }
            val slopePx = with(density) { LOGO_SLOPE.toPx() }
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    colorControls(saturation = LOGO_SATURATION)
                    blur(blurPx)
                    // Asked once on attach, before the first draw has given it a size.
                    val sized = size.isSpecified && size.width > 0f && size.height > 0f
                    if (sized && !glassPerformance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val field = heightField.shaderFor(
                            width = size.width.roundToInt(),
                            height = size.height.roundToInt(),
                            radius = slopePx.roundToInt().coerceAtLeast(1),
                            density = this,
                        )
                        if (field != null) {
                            effect(refraction.effect(field, padding, bendPx, slopePx, darkTheme))
                        }
                    }
                },
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(tint) },
            )
        }
        reduceDynamicBlur || hazeState == null || !isGlassSupported() -> Modifier.background(container)
        else -> Modifier.optimizedHazeEffect(
            state = hazeState,
            style = HazeMaterials.regular(container),
        )
    }

    // The page's shade (the playing record's while the tint is on), turned to
    // ink: same hue, lightness pushed toward the text. Read where it is drawn,
    // so the tint's fade between records repaints the mark without recomposing.
    val pageShade = LocalChromeSurface.current
    val themeSurface = MaterialTheme.colorScheme.surface
    val inkAlpha = if (useGlass) LOGO_GLASS_INK_ALPHA else LOGO_FROST_INK_ALPHA
    val ink: Modifier = Modifier.drawBehind {
        drawRect(logoInk(pageShade() ?: themeSurface, darkTheme).copy(alpha = inkAlpha))
    }

    Box(modifier) {
        if (isGlassSupported()) {
            // The mark's own shadow: lifts it off a flat page, where there is
            // nothing behind it for the glass to bend or the frost to blur, and
            // a mark the colour of the page would otherwise vanish into it.
            Image(
                painter = painter,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.Black.copy(alpha = if (darkTheme) 0.3f else 0.12f)),
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 0.75.dp)
                    .blur(1.dp),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Keep only what falls inside the mark.
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { blendMode = BlendMode.DstIn })
                        drawFitted(painter)
                        canvas.restore()
                    }
                }
                .then(surface)
                .then(ink),
        )
    }
}

/**
 * The mark's colour over its glass or frost: [base]'s hue, its lightness moved
 * most of the way toward the text — deep in light mode, pale in dark — so the
 * mark reads against the page it sits on, as the ink logo used to, while
 * still being the page's own colour.
 */
private fun logoInk(base: Color, dark: Boolean): Color {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(base.toArgb(), it) }
    hsl[2] = if (dark) hsl[2] + (1f - hsl[2]) * LOGO_INK_LIFT else hsl[2] * LOGO_INK_DEPTH
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Light page ~0.92 -> ink ~0.28: clearly dark, still tinted. */
private const val LOGO_INK_DEPTH = 0.3f

/** Dark page ~0.13 -> ink ~0.78. */
private const val LOGO_INK_LIFT = 0.75f

/** Under glass the ink is lighter-handed, so the bent page still shows through it. */
private const val LOGO_GLASS_INK_ALPHA = 0.38f
private const val LOGO_FROST_INK_ALPHA = 0.7f

/**
 * The mark drawn the way [androidx.compose.material3.Icon] drew it — fitted
 * inside the box at its own proportions (it is half again as wide as it is
 * tall) and centred — rather than stretched to fill a square. The mask, the
 * height field and the shadow all have to agree on this, or the glass and its
 * shadow land on different shapes.
 */
private fun DrawScope.drawFitted(painter: Painter) {
    val intrinsic = painter.intrinsicSize
    if (!intrinsic.isSpecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        with(painter) { draw(size) }
        return
    }
    val scale = min(size.width / intrinsic.width, size.height / intrinsic.height)
    val fitted = Size(intrinsic.width * scale, intrinsic.height * scale)
    translate(left = (size.width - fitted.width) / 2f, top = (size.height - fitted.height) / 2f) {
        with(painter) { draw(fitted) }
    }
}

/**
 * The mark's alpha, softened into a height field the refraction reads its
 * slope off: flat on the mark's spine, falling away across its edges. Built
 * on the CPU once per size — the mark is small, so it is a few thousand
 * pixels, and it never changes after.
 */
private class LogoHeightField(private val painter: Painter) {
    private var builtFor: Triple<Int, Int, Int>? = null
    private var shader: BitmapShader? = null

    fun shaderFor(width: Int, height: Int, radius: Int, density: Density): BitmapShader? {
        if (width <= 0 || height <= 0) return null
        val key = Triple(width, height, radius)
        if (key == builtFor) return shader

        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawFitted(painter)
        }
        val pixels = IntArray(width * height)
        image.readPixels(pixels)
        var alpha = FloatArray(pixels.size) { (pixels[it] ushr 24) / 255f }
        // Two box passes each way come out close to a gaussian.
        repeat(2) {
            alpha = boxBlur(alpha, width, height, radius, horizontal = true)
            alpha = boxBlur(alpha, width, height, radius, horizontal = false)
        }
        val out = IntArray(alpha.size) { ((alpha[it].coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0xFFFFFF }
        val bitmap = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        builtFor = key
        return shader
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean): FloatArray {
        val dst = FloatArray(src.size)
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val span = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Float {
                val c = i.coerceIn(0, length - 1)
                // Outside the image is outside the mark.
                if (i != c) return 0f
                return if (horizontal) src[line * w + c] else src[c * w + line]
            }
            var sum = 0f
            for (i in -r..r) sum += at(i)
            for (i in 0 until length) {
                val index = if (horizontal) line * w + i else i * w + line
                dst[index] = sum / span
                sum += at(i + r + 1) - at(i - r)
            }
        }
        return dst
    }
}

/**
 * Bends what is behind the mark along its outline, the way the backdrop's lens
 * does along a rounded rectangle: each pixel near an edge is drawn from a
 * little further inside the glass, most at the edge itself, so the page seems
 * to curve over the mark's rounded sides. The slope of the same field lights
 * the rim from above-left.
 */
private class LogoRefraction {
    private var shader: Any? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun effect(
        field: BitmapShader,
        padding: Float,
        bendPx: Float,
        slopePx: Float,
        darkTheme: Boolean,
    ): androidx.compose.ui.graphics.RenderEffect {
        val runtime = (shader as? android.graphics.RuntimeShader)
            ?: android.graphics.RuntimeShader(LOGO_REFRACTION_SHADER).also { shader = it }
        runtime.setInputShader("height", field)
        runtime.setFloatUniform("offset", padding, padding)
        runtime.setFloatUniform("bend", bendPx)
        runtime.setFloatUniform("slope", 2f * slopePx + 1f)
        runtime.setFloatUniform("rim", if (darkTheme) 0.45f else 0.6f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(runtime, "content").asComposeRenderEffect()
    }
}

private const val LOGO_REFRACTION_SHADER = """
uniform shader content;
uniform shader height;
uniform float2 offset;
uniform float bend;
uniform float slope;
uniform float rim;

half4 main(float2 coord) {
    float2 p = coord - offset;
    float gx = height.eval(p + float2(1.0, 0.0)).a - height.eval(p - float2(1.0, 0.0)).a;
    float gy = height.eval(p + float2(0.0, 1.0)).a - height.eval(p - float2(0.0, 1.0)).a;
    float2 g = float2(gx, gy) * 0.5;
    float edge = clamp(length(g) * slope, 0.0, 1.0);
    float2 inward = length(g) > 0.00001 ? normalize(g) : float2(0.0);
    half4 c = content.eval(coord + inward * edge * bend);
    // The outward side facing the light (up and to the left) catches it.
    float facing = max(0.0, dot(-inward, normalize(float2(-0.6, -1.0))));
    c.rgb += half3(edge * edge * (0.35 + 0.65 * facing) * rim) * c.a;
    return c;
}
"""

/** How far the page is bent at the mark's edge. */
private val LOGO_BEND = 6.dp

/** How wide the rounded edge is: the softening of the mark's alpha. */
private val LOGO_SLOPE = 1.5.dp

/** The frost under the bend; less than the bars', the mark is small. */
private val LOGO_BLUR = 3.dp

private const val LOGO_SATURATION = 1.5f

/** The theme's glass tint, as on the bars. */
private const val LOGO_SURFACE_OPACITY = 0.14f
