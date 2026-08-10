precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
out vec4 Output;
void main() {
    Output = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0);
}
