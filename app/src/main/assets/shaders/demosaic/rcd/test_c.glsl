#define LAYOUT //
LAYOUT
precision highp float;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    // Output Raw Bayer Value
    float b = imageLoad(inTexture, pos).r;
    imageStore(outTexture, pos, vec4(vec3(b), 1.0));
}
