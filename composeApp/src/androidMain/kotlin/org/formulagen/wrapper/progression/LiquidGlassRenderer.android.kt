package org.formulagen.wrapper.progression

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/** Android 13+ evaluates the shared material with AGSL. */
internal actual class LiquidGlassRenderer actual constructor(backdrop: ImageBitmap) {
    private val shader = RuntimeShader(LIQUID_GLASS_SHADER).apply {
        setInputShader("scene", BitmapShader(backdrop.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        })
    }

    /** Match the Skia uniform contract in physical pixels. */
    actual fun brush(uniforms: GlassUniforms): Brush {
        shader.setFloatUniform("extent", uniforms.size.width, uniforms.size.height)
        shader.setFloatUniform("origin", uniforms.origin.x, uniforms.origin.y)
        shader.setFloatUniform("radius", uniforms.radius)
        shader.setFloatUniform("density", uniforms.density)
        shader.setFloatUniform("emphasis", uniforms.emphasis)
        shader.setFloatUniform("tint", uniforms.tint.red, uniforms.tint.green, uniforms.tint.blue)
        return ShaderBrush(shader)
    }

    /** Android owns the lifetime of its managed shader objects. */
    actual fun close() = Unit
}
