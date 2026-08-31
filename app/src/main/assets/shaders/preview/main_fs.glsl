#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTexture;
uniform vec2 resolution;
uniform bool enablePeak;
uniform bool mirror;
uniform bool enableColorLut;
uniform highp sampler3D colorLut;
uniform bool enableIspPreview;
uniform sampler2D ispPreview;
out vec4 Output;
in vec2 texCoord;
void main() {
    vec2 uv = texCoord.xy;
    if(mirror)
        uv.y = 1.0 - uv.y;
    if (enableIspPreview) {
        Output = vec4(texture(ispPreview, uv).rgb, 1.0);
        return;
    }
    vec4 sourceColor = texture(sTexture, uv);
    vec4 color = sourceColor;
    if (enableColorLut) {
        // Map [0,1] onto the centers of the first/last 17^3 texels.
        vec3 lutUv = (clamp(sourceColor.rgb, 0.0, 1.0) * 16.0 + 0.5) / 17.0;
        color.rgb = texture(colorLut, lutUv).rgb;
    }
    vec2 size = resolution;
    // focus peaking
    vec4 avg = vec4(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            avg += texture(sTexture, uv + vec2(i*2, j*2) / size);
        }
    }
    avg /= 9.0;
    float diff = dot(abs(sourceColor - avg), vec4(0.299, 0.587, 0.114, 0.0));
    float denoiseK = 0.05;
    // denoise
    float w = (diff * diff) /(denoiseK + (diff * diff));
    vec4 dc = vec4(1.0,0.0,1.0,0.0);
    if(enablePeak)
        color = color + dc*32.0*diff*w;
    Output = color;
}
