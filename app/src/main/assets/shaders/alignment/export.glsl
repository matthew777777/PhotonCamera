precision highp float;
precision highp sampler2D;
uniform highp sampler2D inTexture;
uniform int yOffset;
#define TILE 2
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    Output = texelFetch(inTexture, xy, 0);
}
