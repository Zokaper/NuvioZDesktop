package com.nuvio.app.features.reaction

import java.util.Locale

/** A reactor tile: [source] is a 0..1 region of the reaction frame, [target] a 0..1 region of the stage. */
internal data class ShaderTile(val source: LayoutRect, val target: LayoutRect)

/**
 * Generates the libplacebo user shader that turns the reaction frame into the stage picture
 * (proven in Stage 0 / S5: ~0.3 ms per frame on an integrated GPU). The hook runs at the stage's
 * output size, so each tile is sampled once, straight from the decoded frame, with Catmull-Rom.
 *
 * Live parameters (change through `glsl-shader-opts` without reloading the shader):
 * - `dim` 0..1: the gap state, every tile darkened.
 * - `blackout` bit mask: per-tile "this reactor went dark", drawn dimmed and desaturated.
 *   A float carries 24 exact bits, so the mask covers the first 24 tiles.
 */
internal object ReactionShader {
    const val PARAM_DIM = "dim"
    const val PARAM_BLACKOUT = "blackout"

    fun build(
        tiles: List<ShaderTile>,
        cornerRadiusPx: Double = 10.0,
        background: Triple<Double, Double, Double> = Triple(0.055, 0.059, 0.075),
    ): String {
        val f = { v: Double -> String.format(Locale.ROOT, "%.6f", v) }
        val tileCode = StringBuilder()
        tiles.forEachIndexed { index, tile ->
            val t = tile.target
            val s = tile.source
            tileCode.append("    TILE($index, vec4(${f(t.x)}, ${f(t.y)}, ${f(t.right)}, ${f(t.bottom)}), ")
            tileCode.append("vec4(${f(s.x)}, ${f(s.y)}, ${f(s.right)}, ${f(s.bottom)}))\n")
        }
        val (r, g, b) = background
        return """
//!PARAM $PARAM_DIM
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM $PARAM_BLACKOUT
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 16777215.0
0.0

//!HOOK MAIN
//!BIND HOOKED
//!WIDTH OUTPUT.w
//!HEIGHT OUTPUT.h
//!DESC nuvio reaction stage (${tiles.size} tiles)

vec4 sampleCatmullRom(vec2 uv) {
    vec2 texSize = HOOKED_size;
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 texPos0 = (texPos1 - 1.0) / texSize;
    vec2 texPos3 = (texPos1 + 2.0) / texSize;
    vec2 texPos12 = (texPos1 + offset12) / texSize;
    vec4 c = vec4(0.0);
    c += HOOKED_tex(vec2(texPos0.x, texPos0.y)) * w0.x * w0.y;
    c += HOOKED_tex(vec2(texPos12.x, texPos0.y)) * w12.x * w0.y;
    c += HOOKED_tex(vec2(texPos3.x, texPos0.y)) * w3.x * w0.y;
    c += HOOKED_tex(vec2(texPos0.x, texPos12.y)) * w0.x * w12.y;
    c += HOOKED_tex(vec2(texPos12.x, texPos12.y)) * w12.x * w12.y;
    c += HOOKED_tex(vec2(texPos3.x, texPos12.y)) * w3.x * w12.y;
    c += HOOKED_tex(vec2(texPos0.x, texPos3.y)) * w0.x * w3.y;
    c += HOOKED_tex(vec2(texPos12.x, texPos3.y)) * w12.x * w3.y;
    c += HOOKED_tex(vec2(texPos3.x, texPos3.y)) * w3.x * w3.y;
    return c;
}

bool tileBlackedOut(int index) {
    if (index >= 24) return false;
    float bits = floor($PARAM_BLACKOUT / exp2(float(index)));
    return mod(bits, 2.0) >= 1.0;
}

#define TILE(I, D, S) if (p.x >= D.x && p.y >= D.y && p.x < D.z && p.y < D.w) { ${'\\'}
        vec2 local = (p - D.xy) / (D.zw - D.xy); ${'\\'}
        vec2 px = (p - D.xy) * target_size; ${'\\'}
        vec2 size = (D.zw - D.xy) * target_size; ${'\\'}
        vec2 q = abs(px - size * 0.5) - (size * 0.5 - radius); ${'\\'}
        float edge = clamp(0.5 - (length(max(q, 0.0)) - radius), 0.0, 1.0); ${'\\'}
        vec4 c = sampleCatmullRom(mix(S.xy, S.zw, local)); ${'\\'}
        if (tileBlackedOut(I)) { float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)); c.rgb = vec3(l) * 0.35; } ${'\\'}
        c.rgb *= (1.0 - 0.65 * $PARAM_DIM); ${'\\'}
        return mix(background, c, edge); ${'\\'}
    }

vec4 hook() {
    vec2 p = HOOKED_pos;
    const float radius = ${f(cornerRadiusPx)};
    vec4 background = vec4(${f(r)}, ${f(g)}, ${f(b)}, 1.0);
$tileCode    return background;
}
""".trimStart()
    }

    /** `glsl-shader-opts` value for the live parameters. */
    fun options(dim: Double, blackoutMask: Int): String =
        String.format(Locale.ROOT, "%s=%.3f,%s=%d", PARAM_DIM, dim.coerceIn(0.0, 1.0), PARAM_BLACKOUT, blackoutMask and 0xFFFFFF)
}
