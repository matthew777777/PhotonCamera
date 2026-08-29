#version 310 es

precision highp float;
precision highp sampler2D;

uniform sampler2D MaskBuffer;
out vec3 Output;

void main() {
    float mask = texelFetch(MaskBuffer, ivec2(gl_FragCoord.xy), 0).r;
    Output = vec3(mask);
}
