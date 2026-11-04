package com.govorun.echo.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sqrt

/**
 * Slow colour waves behind a card that is listening: the theme's colours drift evenly over its surface, whatever the voice does.
 * Below Android 13 there are no runtime shaders, and the card keeps its plain surface.
 */
@Composable
internal fun VoiceFlow(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(modifier.fillMaxSize().background(colors.surfaceContainerHigh))
        return
    }
    val shader = remember { runCatching { RuntimeShader(SHADER) }.getOrNull() }
    if (shader == null) {
        Box(modifier.fillMaxSize().background(colors.surfaceContainerHigh))
        return
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("strength", STRENGTH)
        val base = colors.surfaceContainerHigh
        shader.setColorUniform("base", base.toArgb())
        shader.setColorUniform("one", visible(colors.primary, base, ECHO_CORAL).toArgb())
        shader.setColorUniform("two", visible(colors.tertiary, base, ECHO_CORAL).toArgb())
        shader.setColorUniform("three", visible(colors.secondary, base, colors.primary).toArgb())
        drawRect(brush)
    }
}

/**
 * The theme's colour, or Echo's own when the wallpaper's palette (dynamic colours) gives one too close to the card to be seen:
 * a grey-blue wallpaper must not leave the waves invisible.
 */
internal fun visible(
    color: Color,
    base: Color,
    fallback: Color,
): Color {
    val r = color.red - base.red
    val g = color.green - base.green
    val b = color.blue - base.blue
    return if (sqrt(r * r + g * g + b * b) >= MIN_DISTANCE) color else lerp(color, fallback, 0.7f)
}

private val ECHO_CORAL = Color(0xFFFF6B57)
private const val MIN_DISTANCE = 0.3f
private const val LOOP_MS = 16_000
private const val STRENGTH = 0.55f

// Three soft bands crossing each other at different speeds; their mix tints the card's own surface.
private const val SHADER = """
    uniform float2 size;
    uniform float phase;
    uniform float strength;
    layout(color) uniform half4 base;
    layout(color) uniform half4 one;
    layout(color) uniform half4 two;
    layout(color) uniform half4 three;

    half4 main(float2 at) {
        float2 p = at / max(size.x, 1.0) * 2.2;
        float t = phase * 6.2831853;
        float bend = sin(p.x * 2.2 + t) * 0.6 + sin(p.y * 1.6 - t) * 0.4;
        float a = 0.5 + 0.5 * sin(p.y * 2.8 + p.x * 1.2 + bend - t);
        float b = 0.5 + 0.5 * sin(p.y * 1.9 - p.x * 2.3 - bend * 0.8 + t);
        float c = 0.5 + 0.5 * sin((p.x - p.y) * 1.5 + bend * 0.5 + t * 2.0);
        half3 tint = mix(mix(one.rgb, two.rgb, half(a)), three.rgb, half(c * 0.5));
        half amount = half(strength * (0.45 + 0.3 * a + 0.25 * b));
        return half4(mix(base.rgb, tint, amount), 1.0);
    }
"""
