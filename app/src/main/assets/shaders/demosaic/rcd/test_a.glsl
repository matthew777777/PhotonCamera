#define LAYOUT //
LAYOUT
precision highp float;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    // Output constant Red
    imageStore(outTexture, pos, vec4(1.0, 0.0, 0.0, 1.0));
}
