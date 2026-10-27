package org.formulagen.wrapper.progression

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** Pixel coordinates locate a card in its Compose-owned scene. */
internal data class GlassUniforms(
    val size: Size, val origin: Offset, val radius: Float,
    val density: Float, val emphasis: Float, val tint: Color,
)

/** Compile once per material and release platform resources when the scene leaves. */
internal expect class LiquidGlassRenderer(backdrop: ImageBitmap) {
    fun brush(uniforms: GlassUniforms): Brush
    fun close()
}

/** One shader source for Android AGSL and desktop/iOS SkSL. */
internal const val LIQUID_GLASS_SHADER = """
uniform shader scene;
uniform float2 extent;
uniform float2 origin;
uniform float radius;
uniform float density;
uniform float emphasis;
uniform float3 tint;

float roundedDistance(float2 p) {
    float2 q = abs(p - extent * 0.5) - extent * 0.5 + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

half4 main(float2 p) {
    float d = roundedDistance(p);
    float e = max(density, 1.0);
    float2 gradient = float2(roundedDistance(p + float2(e, 0.0)) - roundedDistance(p - float2(e, 0.0)),
                             roundedDistance(p + float2(0.0, e)) - roundedDistance(p - float2(0.0, e)));
    float2 normal = gradient / max(length(gradient), 0.001);
    float bevel = exp(-abs(d) / (10.0 * density));
    float2 uv = origin + p - normal * bevel * (13.0 + emphasis * 5.0) * density;
    float blur = (2.6 - emphasis * 0.8) * density;
    half3 color = scene.eval(uv).rgb * 0.28;
    color += (scene.eval(uv + float2(blur, 0.0)).rgb + scene.eval(uv - float2(blur, 0.0)).rgb
           + scene.eval(uv + float2(0.0, blur)).rgb + scene.eval(uv - float2(0.0, blur)).rgb) * 0.12;
    color += (scene.eval(uv + float2(blur, blur)).rgb + scene.eval(uv - float2(blur, blur)).rgb
           + scene.eval(uv + float2(blur, -blur)).rgb + scene.eval(uv + float2(-blur, blur)).rgb) * 0.06;
    float luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
    color = mix(half3(luminance), color, 1.12);
    color = mix(color, half3(0.045, 0.075, 0.085), 0.18);
    color += half3(tint) * (0.035 + emphasis * 0.065);
    color *= min(1.0, 0.24 / max(max(color.r, color.g), max(color.b, 0.001)));
    float lip = exp(-abs(d + density) / (1.15 * density));
    float light = 0.22 + 0.58 * max(dot(normal, normalize(float2(-0.65, -0.8))), 0.0);
    color += half3(lip * light * (0.55 + emphasis * 0.25));
    color += half3(tint) * bevel * (0.025 + emphasis * 0.045);
    return half4(clamp(color, 0.0, 1.0), 1.0);
}
"""
