#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp sampler2D inTexture;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;
uniform ivec2 shift;

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 align = texelFetch(inTexture,xy,0);
    imageStore(outTexture, xy + shift, align);
}