package org.formulagen.wrapper.progression

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

/** Desktop and iOS share the same Skia material implementation. */
internal actual class LiquidGlassRenderer actual constructor(backdrop: ImageBitmap) {
    private val effect = RuntimeEffect.makeForShader(LIQUID_GLASS_SHADER)
    private val builder = RuntimeShaderBuilder(effect)
    private val scene = backdrop.asSkiaBitmap().makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
        SamplingMode.LINEAR)
    private var material: Shader? = null

    /** Update only uniforms when selection or layout changes. */
    actual fun brush(uniforms: GlassUniforms): Brush {
        builder.child("scene", scene)
        builder.uniform("extent", uniforms.size.width, uniforms.size.height)
        builder.uniform("origin", uniforms.origin.x, uniforms.origin.y)
        builder.uniform("radius", uniforms.radius)
        builder.uniform("density", uniforms.density)
        builder.uniform("emphasis", uniforms.emphasis)
        builder.uniform("tint", uniforms.tint.red, uniforms.tint.green, uniforms.tint.blue)
        material?.close()
        return ShaderBrush(builder.makeShader().also { material = it }.asComposeShader())
    }

    /** Free the shader and its retained scene when replaced or dismissed. */
    actual fun close() { material?.close(); scene.close(); builder.close(); effect.close() }
}
