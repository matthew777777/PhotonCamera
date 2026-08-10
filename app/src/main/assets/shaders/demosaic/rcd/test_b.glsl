#define LAYOUT //
LAYOUT
precision highp float;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(outTexture);
    // Output UV coordinates (R=X, G=Y)
    vec2 uv = vec2(pos) / vec2(size);
    imageStore(outTexture, pos, vec4(uv, 0.0, 1.0));
}
