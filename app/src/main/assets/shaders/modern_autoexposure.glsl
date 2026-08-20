precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float exposureScale;

out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    // Input is scene-referred linear RGB from ModernInitial
    vec3 sceneRGB = texelFetch(InputBuffer, xy, 0).rgb;

    // Apply dynamic exposure multiplier calculated by the node.
    // No upper clamp: highlights above 1.0 are valid HDR data for the tone
    // mapper further down the chain. Floored at 0 defensively (exposureScale
    // is already clamped positive on the Java side).
    Output = vec4(max(sceneRGB * exposureScale, 0.0), 1.0);
}
