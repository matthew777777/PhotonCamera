precision highp float;
precision highp sampler2D;

#define FINE_RGB 0
#define FINAL_OUTPUT 0

uniform sampler2D FineBuffer;
uniform sampler2D CoarseBuffer;
uniform sampler2D ReconstructedBuffer;
uniform ivec2 fineSize;
uniform ivec2 coarseSize;
uniform float sigma;
uniform float shadows;
uniform float highlights;
uniform float clarity;

#if FINAL_OUTPUT == 1
out vec4 Output;
#else
out float Output;
#endif

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float fineLuminance(ivec2 p) {
#if FINE_RGB == 1
    return dot(texelFetch(FineBuffer, p, 0).rgb, LUMA);
#else
    return texelFetch(FineBuffer, p, 0).r;
#endif
}

float fetchClamped(sampler2D image, ivec2 p) {
    return texelFetch(image, clamp(p, ivec2(0), coarseSize - ivec2(1)), 0).r;
}

// Exact 2x expansion phase and weights of the binomial pyramid.
float expandGaussian(sampler2D image, ivec2 p) {
    ivec2 c = p / 2;
    bool oddX = (p.x & 1) != 0;
    bool oddY = (p.y & 1) != 0;

    if (!oddX && !oddY) {
        float sum = 36.0 * fetchClamped(image, c);
        sum += 6.0 * (fetchClamped(image, c + ivec2(-1, 0))
                + fetchClamped(image, c + ivec2(1, 0))
                + fetchClamped(image, c + ivec2(0, -1))
                + fetchClamped(image, c + ivec2(0, 1)));
        sum += fetchClamped(image, c + ivec2(-1, -1))
                + fetchClamped(image, c + ivec2(1, -1))
                + fetchClamped(image, c + ivec2(-1, 1))
                + fetchClamped(image, c + ivec2(1, 1));
        return sum / 64.0;
    }
    if (oddX && !oddY) {
        float sum = 6.0 * (fetchClamped(image, c) + fetchClamped(image, c + ivec2(1, 0)));
        sum += fetchClamped(image, c + ivec2(0, -1))
                + fetchClamped(image, c + ivec2(1, -1))
                + fetchClamped(image, c + ivec2(0, 1))
                + fetchClamped(image, c + ivec2(1, 1));
        return sum / 16.0;
    }
    if (!oddX && oddY) {
        float sum = 6.0 * (fetchClamped(image, c) + fetchClamped(image, c + ivec2(0, 1)));
        sum += fetchClamped(image, c + ivec2(-1, 0))
                + fetchClamped(image, c + ivec2(1, 0))
                + fetchClamped(image, c + ivec2(-1, 1))
                + fetchClamped(image, c + ivec2(1, 1));
        return sum / 16.0;
    }
    return 0.25 * (fetchClamped(image, c)
            + fetchClamped(image, c + ivec2(1, 0))
            + fetchClamped(image, c + ivec2(0, 1))
            + fetchClamped(image, c + ivec2(1, 1)));
}

// Local-contrast remapping curve, expressed in GLSL.
float remap(float x, float g) {
    float c = x - g;
    float value;
    if (c > 2.0 * sigma) {
        value = g + sigma + shadows * (c - sigma);
    } else if (c < -2.0 * sigma) {
        value = g - sigma + highlights * (c + sigma);
    } else if (c > 0.0) {
        float t = clamp(c / (2.0 * sigma), 0.0, 1.0);
        value = g + sigma * 2.0 * (1.0 - t) * t
                + t * t * (sigma + sigma * shadows);
    } else {
        float t = clamp(-c / (2.0 * sigma), 0.0, 1.0);
        value = g - sigma * 2.0 * (1.0 - t) * t
                + t * t * (-sigma - sigma * highlights);
    }
    value += clarity * c * exp(-1.5 * c * c / (sigma * sigma));
    return value;
}

float remappedLaplacian(float fine, float coarse, float gamma) {
    return remap(fine, gamma) - remap(coarse, gamma);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float fineL = fineLuminance(p);
    float coarseL = expandGaussian(CoarseBuffer, p);
    float rebuiltBase = expandGaussian(ReconstructedBuffer, p);

    // Six reference luminances are sampled at (k + 0.5) / 6 and the two
    // neighboring remapped Laplacian coefficients are interpolated.
    float anchor = clamp(fineL * 6.0 - 0.5, 0.0, 5.0);
    float lo = min(floor(anchor), 4.0);
    float blend = clamp(anchor - lo, 0.0, 1.0);
    float gamma0 = (lo + 0.5) / 6.0;
    float gamma1 = (lo + 1.5) / 6.0;
    float lap0 = remappedLaplacian(fineL, coarseL, gamma0);
    float lap1 = remappedLaplacian(fineL, coarseL, gamma1);
    float rebuiltL = rebuiltBase + mix(lap0, lap1, blend);

#if FINAL_OUTPUT == 1
    vec4 source = texelFetch(FineBuffer, p, 0);
    float scale = max(rebuiltL, 0.0) / max(fineL, 1e-5);
    Output = vec4(clamp(source.rgb * scale, 0.0, 1.0), source.a);
#else
    Output = rebuiltL;
#endif
}
