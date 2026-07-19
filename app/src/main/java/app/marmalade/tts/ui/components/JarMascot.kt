package app.marmalade.tts.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

// =============================================================================
// JarMascot — the living mascot, drawn (not a static drawable)
// =============================================================================
//
// Faithful port of marmalade-android's JarMascot (the approved animation
// suite, agent-wiki tech/design/marmalade-design/suite.html). The static
// mascot_*.xml drawables can only be transformed as a whole; these loops
// need a lid that lifts, waves that drift in, bubbles inside the jar and
// per-expression faces — so the jar is drawn from the same path data the
// drawables use (108×108 viewport, geometry traced from mascot_happy.xml).
//
//   IDLE       breathing + blink (HAPPY face)
//   LISTENING  lid lifts + tilts, waves drift in (ALERT) — used while
//              engine downloads are in flight
//   THINKING   gentle sway, eyes drift up, jam bubbles rise, lid taps
//   SPEAKING   squash-and-stretch bounce, ^‿^ eyes, mouth rides the level
//   ERROR      worried face, small shiver
//
// One rememberInfiniteTransition clock; all motion is a pure function of it.

enum class JarMascotState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/** Organic fake activity level in [0,1] — same curve as the source app. */
private fun fakeAmp(tMs: Float): Float =
    (.45f + .3f * sin(tMs * .0027f) + .2f * sin(tMs * .0013f + 1.7f)).coerceIn(.05f, 1f)

// ── Palette (asset colors from mascot_happy.xml — not theme-dependent) ──────

private val BODY = Color(0xFFF5A623)
private val MARMALADE = Color(0x80D4831E)
private val SHINE = Color(0x26FFFFFF)
private val NECK = Color(0xFFE8D5B0)
private val LID = Color(0xFFFFF8EE)
private val LID_STROKE = Color(0xFFD4C4A0)
private val LID_LINES = Color(0x80E0D0B0)
private val LABEL = Color(0xFFFFF8EE)
private val LABEL_STROKE = Color(0xFFD4B896)
private val LABEL_LINES = Color(0xFFE8C9A0)
private val SLICE_STROKE = Color(0xFFD4831E)
private val INK = Color(0xFF3D2B1F)
private val BLUSH = Color(0x1AFF6B6B)
private val SHADOW = Color(0x14000000)
private val BUBBLE = Color(0x8CFFF8EE)

// ── Static geometry (parsed once) ───────────────────────────────────────────

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private class JarGeometry {
    val body = path(
        "M36.3,35.3 L71.6,35.3 Q79.9,35.3 79.9,43.6 L79.9,89.3 Q79.9,97.6 71.6,97.6 " +
            "L36.3,97.6 Q28.0,97.6 28.0,89.3 L28.0,43.6 Q28.0,35.3 36.3,35.3 Z",
    )
    val marmalade = path(
        "M36.7,62.3 L71.3,62.3 Q77.9,62.3 77.9,68.9 L77.9,89.3 Q77.9,95.9 71.3,95.9 " +
            "L36.7,95.9 Q30.1,95.9 30.1,89.3 L30.1,68.9 Q30.1,62.3 36.7,62.3 Z",
    )
    val shine = path(
        "M34.3,37.4 Q38.4,37.4 38.4,41.6 L38.4,87.2 Q38.4,91.4 34.2,91.4 " +
            "Q30.1,91.4 30.1,87.2 L30.1,41.6 Q30.1,37.4 34.3,37.4 Z",
    )
    val neck = path(
        "M37.0,32.2 L71.0,32.2 Q72.7,32.2 72.7,33.9 L72.7,35.7 Q72.7,37.4 71.0,37.4 " +
            "L37.0,37.4 Q35.3,37.4 35.3,35.7 L35.3,33.9 Q35.3,32.2 37.0,32.2 Z",
    )
    val label = path(
        "M38.4,78.9 L69.5,78.9 Q71.6,78.9 71.6,81.0 L71.6,90.3 Q71.6,92.4 69.5,92.4 " +
            "L38.4,92.4 Q36.3,92.4 36.3,90.3 L36.3,81.0 Q36.3,78.9 38.4,78.9 Z",
    )
    val lidBody = path(
        "M35.1,22.8 L72.9,22.8 Q75.8,22.8 75.8,25.7 L75.8,30.7 Q75.8,33.6 72.9,33.6 " +
            "L35.1,33.6 Q32.2,33.6 32.2,30.7 L32.2,25.7 Q32.2,22.8 35.1,22.8 Z",
    )
    val lidRidge = path(
        "M35.8,21.8 L72.3,21.8 Q73.8,21.8 73.8,23.3 Q73.8,24.7 72.3,24.7 " +
            "L35.8,24.7 Q34.3,24.7 34.3,23.2 Q34.3,21.8 35.8,21.8 Z",
    )
    val smile = path("M48.5,69.6 Q54.0,72.0 59.5,69.6")
    val frown = path("M48.5,70.5 Q54.0,68.5 59.5,70.5")
    val happyEyeL = path("M40.4,61.6 Q44.6,56.2 48.8,61.6")
    val happyEyeR = path("M59.1,61.6 Q63.3,56.2 67.5,61.6")
}

// ── Public composable ───────────────────────────────────────────────────────

/**
 * The animated jar. Drawn live, so the lid, waves, bubbles and blink all
 * move like the approved suite.
 */
@Composable
fun JarMascot(
    state: JarMascotState,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val geo = remember { JarGeometry() }
    // Mode-aware wave color, derived from the resolved surface luminance
    // (the app theme setting can disagree with the system dark mode).
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val waveColor = if (isDark) Color(0xFFFED7AA) else Color(0xFFF97316)

    val clock = rememberInfiniteTransition(label = "jar_clock")
    val tMs by clock.animateFloat(
        initialValue = 0f,
        targetValue = 60_000f,
        animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing)),
        label = "jar_t",
    )

    Canvas(modifier = modifier.size(size)) {
        val t = tMs
        val a = fakeAmp(t)
        val u = this.size.minDimension / 108f // viewBox unit

        // ---- pose (all in viewBox units) ----
        var stretchY = 1f
        var rot = 0f
        var ty = 0f
        var lidLift = 0f
        var lidTilt = 0f
        when (state) {
            JarMascotState.IDLE -> {
                stretchY = 1f + .015f * sin(t * .0016f)
            }
            JarMascotState.LISTENING -> {
                stretchY = 1.02f + .02f * a
                lidLift = 7f + 4f * a
                lidTilt = -6f - 3f * a
            }
            JarMascotState.THINKING -> {
                rot = 2.2f * sin(t * .0021f)
                lidLift = max(0f, sin(t * .0024f)).pow(12) * 2.5f
            }
            JarMascotState.SPEAKING -> {
                val ph = abs(sin(t * .006f))
                ty = -ph * (3f + 3f * a)
                stretchY = 1f + (ph - .5f) * .06f
            }
            JarMascotState.ERROR -> {
                ty = 0f
                rot = 0f
                stretchY = .99f
            }
        }
        val shiverX = if (state == JarMascotState.ERROR) sin(t * .05f) * .7f else 0f
        val blink = (t % 3400f) < 130f

        scale(u, u, pivot = Offset.Zero) {
            // shadow stays on the ground, outside the squash
            drawOval(SHADOW, topLeft = Offset(29.1f, 98.1f), size = Size(49.8f, 7.4f))

            withTransform({
                translate(shiverX, ty)
                rotate(rot, pivot = Offset(54f, 99f))
                scale(2f - stretchY, stretchY, pivot = Offset(54f, 99f))
            }) {
                drawJarBody(geo)
                if (state == JarMascotState.THINKING) {
                    drawBubbles(geo, t)
                }
                drawFace(geo, state, t, a, blink)
                // lid rides its own transform on top of the body pose
                withTransform({
                    translate(0f, -lidLift)
                    rotate(lidTilt, pivot = Offset(54f, 28f))
                }) {
                    drawLid(geo)
                }
                if (state == JarMascotState.LISTENING) {
                    drawWavesIn(t, a, lidLift, waveColor)
                }
                if (state == JarMascotState.SPEAKING) {
                    drawSpeechArcs(t, a, waveColor)
                }
            }
        }
    }
}

// ── Draw helpers (all in 108×108 viewBox units) ─────────────────────────────

private fun DrawScope.drawJarBody(geo: JarGeometry) {
    drawPath(geo.body, BODY)
    drawPath(geo.marmalade, MARMALADE)
    drawPath(geo.shine, SHINE)
    drawPath(geo.neck, NECK)
    drawPath(geo.label, LABEL)
    drawPath(geo.label, LABEL_STROKE, style = Stroke(width = .3f))
    drawLine(LABEL_LINES, Offset(39.4f, 81.4f), Offset(68.6f, 81.4f), strokeWidth = .2f)
    drawLine(LABEL_LINES, Offset(39.4f, 89.7f), Offset(68.6f, 89.7f), strokeWidth = .2f)
    // orange slice on the label
    drawCircle(BODY, radius = 2.5f, center = Offset(54f, 76.9f))
    drawCircle(SLICE_STROKE, radius = 2.5f, center = Offset(54f, 76.9f), style = Stroke(.3f))
}

private fun DrawScope.drawLid(geo: JarGeometry) {
    drawPath(geo.lidBody, LID)
    drawPath(geo.lidBody, LID_STROKE, style = Stroke(.4f))
    drawPath(geo.lidRidge, LID)
    drawPath(geo.lidRidge, LID_STROKE, style = Stroke(.3f))
    for (x in intArrayOf(37, 43, 50, 56, 62)) {
        drawLine(LID_LINES, Offset(x + .4f, 24.2f), Offset(x + .4f, 32.6f), strokeWidth = .2f)
    }
}

private fun DrawScope.drawFace(
    geo: JarGeometry,
    state: JarMascotState,
    t: Float,
    amp: Float,
    blink: Boolean,
) {
    val stroke = Stroke(width = .9f, cap = StrokeCap.Round)
    when (state) {
        JarMascotState.SPEAKING -> {
            // ^‿^ eyes + mouth synced to the level
            drawPath(geo.happyEyeL, INK, style = Stroke(1.8f, cap = StrokeCap.Round))
            drawPath(geo.happyEyeR, INK, style = Stroke(1.8f, cap = StrokeCap.Round))
            drawOval(
                INK,
                topLeft = Offset(51f, 70.4f - (1f + 2.6f * amp)),
                size = Size(6f, 2f * (1f + 2.6f * amp)),
            )
            drawBlush()
        }
        JarMascotState.LISTENING -> {
            // ALERT: bigger eyes (suite-tuned 5.1×6.0 per Max), parted lips
            eyes(rx = 5.1f, ry = 6f, hlR = 2.1f, hlDy = -2.3f)
            drawOval(INK, topLeft = Offset(50f, 68.8f), size = Size(8f, 2.4f))
        }
        JarMascotState.THINKING -> {
            // eyes drift up, o-mouth
            translate(1.4f, -2.2f) { eyes(rx = 4.6f, ry = 5.4f, hlR = 1.9f, hlDy = -2f) }
            drawCircle(INK, radius = 1.7f, center = Offset(54f, 70.2f), style = stroke)
        }
        JarMascotState.ERROR -> {
            // worried: soft brows, smaller eyes, gentle frown
            drawLine(INK, Offset(40.5f, 55.5f), Offset(48f, 57f), strokeWidth = .5f, cap = StrokeCap.Round)
            drawLine(INK, Offset(60f, 57f), Offset(67.5f, 55.5f), strokeWidth = .5f, cap = StrokeCap.Round)
            eyes(rx = 4f, ry = 4.8f, hlR = 1.4f, hlDy = -1.7f)
            drawPath(geo.frown, INK, style = stroke)
        }
        JarMascotState.IDLE -> {
            if (blink) {
                drawLine(INK, Offset(40.5f, 60.2f), Offset(48.7f, 60.2f), strokeWidth = 1.6f, cap = StrokeCap.Round)
                drawLine(INK, Offset(59.1f, 60.2f), Offset(67.3f, 60.2f), strokeWidth = 1.6f, cap = StrokeCap.Round)
            } else {
                eyes(rx = 4.6f, ry = 5.4f, hlR = 1.9f, hlDy = -2f)
            }
            drawPath(geo.smile, INK, style = stroke)
            drawBlush()
        }
    }
}

/** Oval eyes + white highlights at the canonical eye centers. */
private fun DrawScope.eyes(rx: Float, ry: Float, hlR: Float, hlDy: Float) {
    for (cx in floatArrayOf(44.6f, 63.3f)) {
        drawOval(INK, topLeft = Offset(cx - rx, 60.2f - ry), size = Size(rx * 2, ry * 2))
        drawCircle(Color.White, radius = hlR, center = Offset(cx + 1.9f, 60.2f + hlDy))
    }
}

private fun DrawScope.drawBlush() {
    drawOval(BLUSH, topLeft = Offset(36.5f, 61.7f), size = Size(7f, 3.6f))
    drawOval(BLUSH, topLeft = Offset(64.5f, 61.7f), size = Size(7f, 3.6f))
}

/** Sound waves drifting down into the open lid while listening. */
private fun DrawScope.drawWavesIn(t: Float, amp: Float, lidLift: Float, color: Color) {
    for (k in 0 until 3) {
        val ph = ((t * .0012f + k / 3f) % 1f)
        val alpha = ((1f - ph) * (.3f + .7f * amp)).coerceIn(0f, 1f)
        val dy = ph * 12f - lidLift * .5f
        val halfW = 12f - k * 2f
        val y = 10f + k * 4f + dy
        val path = Path().apply {
            moveTo(54f - halfW, y)
            quadraticBezierTo(54f, y - 5f, 54f + halfW, y)
        }
        drawPath(path, color.copy(alpha = alpha), style = Stroke(2f, cap = StrokeCap.Round))
    }
}

/** Speech arcs radiating from the mouth while talking. */
private fun DrawScope.drawSpeechArcs(t: Float, amp: Float, color: Color) {
    for (k in 0 until 2) {
        val ph = ((t * .0014f + k / 2f) % 1f)
        val alpha = ((1f - ph) * (.25f + .6f * amp)).coerceIn(0f, 1f)
        val r = 7f + ph * 13f
        val c = color.copy(alpha = alpha)
        val style = Stroke(1.6f, cap = StrokeCap.Round)
        drawArc(
            c, startAngle = -50f, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(54f - r, 70.4f - r), size = Size(2 * r, 2 * r), style = style,
        )
        drawArc(
            c, startAngle = 130f, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(54f - r, 70.4f - r), size = Size(2 * r, 2 * r), style = style,
        )
    }
}

/** Jam bubbles rising inside the marmalade fill while thinking. */
private fun DrawScope.drawBubbles(geo: JarGeometry, t: Float) {
    clipPath(geo.marmalade) {
        val xs = floatArrayOf(42f, 54f, 66f)
        val rs = floatArrayOf(2f, 1.4f, 2.4f)
        for (k in 0 until 3) {
            val ph = ((t * .0005f + k * .37f) % 1f)
            drawCircle(
                BUBBLE.copy(alpha = BUBBLE.alpha * sin(ph * PI.toFloat())),
                radius = rs[k],
                center = Offset(xs[k], 95f - ph * 30f),
            )
        }
    }
}
