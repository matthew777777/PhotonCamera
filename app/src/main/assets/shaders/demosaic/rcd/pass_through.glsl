precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
out vec3 Output;
void main() {
    float bayer = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).r;
    Output = vec3(bayer);
}
