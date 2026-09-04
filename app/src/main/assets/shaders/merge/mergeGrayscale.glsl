#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
uniform ivec2 inSize;
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    // pack 4 horizontal luma samples into one rgba16f texel -> 4x smaller in x
    vec4 out4;
    for (int i = 0; i < 4; i++) {
        ivec2 p = ivec2(min(xy.x * 4 + i, inSize.x - 1), min(xy.y, inSize.y - 1));
        vec4 c = imageLoad(inTexture, p);
        out4[i] = dot(c, vec4(0.25));
    }
    imageStore(outTexture, xy, sqrt(out4));
}
