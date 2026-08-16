precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float exposureScale;

out vec4 Output;

#import coords

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    // Input is scene-referred linear RGB from ModernInitial
    vec3 sceneRGB = texelFetch(InputBuffer, xy, 0).rgb;

    // Apply dynamic exposure multiplier calculated by the node
    Output = vec4(sceneRGB * exposureScale, 1.0);
}
