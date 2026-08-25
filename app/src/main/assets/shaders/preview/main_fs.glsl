#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTexture;
uniform vec2 resolution;
uniform bool enablePeak;
uniform bool mirror;
uniform bool enableBilateralGrid;
uniform vec3 bilateralGridSize;
uniform highp sampler3D bilateralGridR;
uniform highp sampler3D bilateralGridG;
uniform highp sampler3D bilateralGridB;
uniform highp sampler3D bilateralGridRPrev;
uniform highp sampler3D bilateralGridGPrev;
uniform highp sampler3D bilateralGridBPrev;
uniform float bguBlend;
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
    if (enableBilateralGrid) {
        float guide = clamp(dot(sourceColor.rgb, vec3(0.299, 0.587, 0.114)), 0.0, 1.0);
        // Address texel centers while making uv=0/1 select the first/last cell.
        vec3 gridPosition = (vec3(uv, guide) * (bilateralGridSize - 1.0) + 0.5)
                / bilateralGridSize;
        vec4 affineInput = vec4(sourceColor.rgb, 1.0);
        vec4 rowR = mix(texture(bilateralGridRPrev, gridPosition),
                        texture(bilateralGridR, gridPosition), bguBlend);
        vec4 rowG = mix(texture(bilateralGridGPrev, gridPosition),
                        texture(bilateralGridG, gridPosition), bguBlend);
        vec4 rowB = mix(texture(bilateralGridBPrev, gridPosition),
                        texture(bilateralGridB, gridPosition), bguBlend);
        color.rgb = vec3(
                dot(rowR, affineInput),
                dot(rowG, affineInput),
                dot(rowB, affineInput));
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
