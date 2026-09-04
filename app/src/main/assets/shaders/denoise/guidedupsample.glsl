
#define SCALE 4
precision highp float;
precision highp sampler2D;
uniform sampler2D LowresInput;
uniform sampler2D GuideHigh;
out vec3 Output;

// Gaussian-weighted least-squares fit of the lowres input against the guide
// lightness, over a window of radius R centred at highres position `center`.
// Returns the model evaluated at this pixel's guide lightness.
const int R = SCALE;
const float sigma = 0.4 * float(R);
const float sigmaSq2 = 2.0 * sigma * sigma;
vec3 fitModel(ivec2 center, vec2 lowSize, ivec2 highMax, float guideLightness) {
    float momentX  = 0.0;
    vec3  momentY  = vec3(0.0);
    float momentX2 = 0.0;
    vec3  momentXY = vec3(0.0);
    float ws = 0.0;
    for (int i = -R; i <= R; i++) {
        float wi = exp(-float(i * i) / sigmaSq2);
        for (int j = -R; j <= R; j++) {
            float w = wi * exp(-float(j * j) / sigmaSq2);
            ivec2 pos = clamp(center + ivec2(i, j), ivec2(0), highMax);
            float lightness = dot(texelFetch(GuideHigh, pos, 0).rgb, vec3(1.0/3.0));
            // Bilinear lowres lookup. gaussdown anchors lowres texel (i,j) at
            // highres (i*SCALE, j*SCALE), so highres p maps to continuous
            // lowres texel coordinate p/SCALE.
            //vec3 lowresVal = textureLod(LowresInput, (vec2(pos) / float(SCALE) + 0.5) / lowSize, 0.0).rgb;
            vec3 lowresVal = texelFetch(LowresInput, pos/SCALE, 0).rgb;
            momentX  += lightness * w;
            momentY  += lowresVal * w;
            momentX2 += lightness * lightness * w;
            momentXY += lightness * lowresVal * w;
            ws       += w;
        }
    }
    float invWs = 1.0 / ws;
    float meanX = momentX * invWs;
    vec3  meanY = momentY * invWs;
    float varX  = momentX2 * invWs - meanX * meanX;
    vec3  covXY = momentXY * invWs - meanX * meanY;
    vec3 a = covXY / (max(varX, 0.0) + 3e-04);
    vec3 b = meanY - a * meanX;
    return a * guideLightness + b;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec2 lowSize = vec2(textureSize(LowresInput, 0));
    ivec2 highMax = textureSize(GuideHigh, 0) - ivec2(1);
    float guideLightness = dot(texelFetch(GuideHigh, xy, 0).rgb, vec3(1.0/3.0));

    // Continuous lowres texel coordinate of this pixel; the four surrounding
    // parent anchors each get their own local linear model, evaluated with
    // THIS pixel's guide lightness, then blended bilinearly. The blend weights
    // match the lowres grid exactly, so the upsampled result is continuous
    // across block boundaries - no sliding-window snapping, no rectangles.
    vec2 t = vec2(xy) / float(SCALE);
    ivec2 base = ivec2(floor(t));
    vec2 f = t - vec2(base);
    ivec2 lowMax = ivec2(lowSize) - ivec2(1);
    //vec3 y00 = fitModel(clamp(base,               ivec2(0), lowMax) * SCALE, lowSize, highMax, guideLightness);
    //vec3 y10 = fitModel(clamp(base + ivec2(1, 0), ivec2(0), lowMax) * SCALE, lowSize, highMax, guideLightness);
    //vec3 y01 = fitModel(clamp(base + ivec2(0, 1), ivec2(0), lowMax) * SCALE, lowSize, highMax, guideLightness);
    //vec3 y11 = fitModel(clamp(base + ivec2(1, 1), ivec2(0), lowMax) * SCALE, lowSize, highMax, guideLightness);
    //Output = mix(mix(y00, y10, f.x), mix(y01, y11, f.x), f.y);
    Output = fitModel(xy, lowSize, highMax, guideLightness);

    // Chroma direction from the blended model, luminance from the guide:
    // rescale so the output lightness equals the guide lightness exactly.
    // The floor keeps the scale factor <= 4, so near-dark fits can never
    // explode into singular values.
    float outL = dot(Output, vec3(1.0/3.0));
    Output *= guideLightness / max(outL, 0.25 * guideLightness);
}
