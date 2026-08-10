precision highp float;
precision highp sampler2D;

uniform sampler2D InputBuffer;
uniform float strength;
uniform float threshold;
uniform float noiseFloor;
uniform int debugResponse;

out vec4 Output;

// A 5x5 kernel for capture sharpening to recover demosaic-related softness
// without introducing heavy halos (small radius)
#define KERNEL_SIZE 2

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec3 center = texelFetch(InputBuffer, xy, 0).rgb;

    // Use Luminance for sharpening to prevent color fringing
    float lumaCenter = (center.r + center.g + center.b) / 3.0;

    float lumaBlur = 0.0;
    float weightSum = 0.0;

    // Gaussian-like blur using a box approximation for efficiency
    for(int j = -KERNEL_SIZE; j <= KERNEL_SIZE; j++) {
        for(int i = -KERNEL_SIZE; i <= KERNEL_SIZE; i++) {
            vec3 samp = texelFetch(InputBuffer, xy + ivec2(i, j), 0).rgb;
            float l = (samp.r + samp.g + samp.b) / 3.0;
            // Simple weighting: central pixels matter more
            float w = 1.0 / (1.0 + float(i*i + j*j));
            lumaBlur += l * w;
            weightSum += w;
        }
    }
    lumaBlur /= weightSum;

    // Mask represents high-frequency detail
    float mask = lumaCenter - lumaBlur;

    // Apply Noise Awareness and Contrast Limiting
    float absMask = abs(mask);
    float sharpenedMask = 0.0;

    if (absMask > noiseFloor) {
        // Soft thresholding to avoid harsh transitions
        sharpenedMask = sign(mask) * max(0.0, absMask - threshold);
    }

    if (debugResponse == 1) {
        // Visualize the sharpening mask (magnified)
        Output = vec4(vec3(abs(sharpenedMask) * 10.0), 1.0);
        return;
    }

    float resLuma = lumaCenter + sharpenedMask * strength;

    // Apply gain to color channels based on sharpened luminance
    // This preserves hue and saturation
    float ratio = resLuma / (lumaCenter + 0.00001);

    // Limit ratio to prevent extreme highlights/artifacts
    ratio = clamp(ratio, 0.5, 3.0);

    Output = vec4(clamp(center * ratio, 0.0, 1.0), 1.0);
}
