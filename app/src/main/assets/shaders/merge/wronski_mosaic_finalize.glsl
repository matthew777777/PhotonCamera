precision highp float;
precision highp sampler2D;

uniform highp sampler2D accumulator;
uniform float whiteLevel;
uniform int yOffset;
out highp uint Output;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    p.y += yOffset;
    vec2 a = texelFetch(accumulator, p, 0).rg;
    float v = clamp(a.x / max(a.y, 1e-6), 0.0, 1.0);
    Output = uint(round(v * whiteLevel));
}
