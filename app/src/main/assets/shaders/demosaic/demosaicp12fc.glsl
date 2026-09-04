#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
//uniform sampler2D bayerTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
//uniform sampler2D greenTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp readonly image2D igTexture;
layout(rgba16f, binding = 3) uniform highp writeonly image2D outTexture;
#define alpha 3.75
#define L 7
#define THRESHOLD 1.3
#define EPS 0.0001
uniform int yOffset;

// Helper function to get Bayer sample
float getBayerSample(ivec2 pos) {
    return imageLoad(inTexture, pos).r;
    //return texelFetch(bayerTexture, pos, 0).r;
}

// Helper function to determine Bayer pattern at a given position
// 0: R, 1: G (at red row), 2: G (at blue row), 3: B
int getBayerPattern(ivec2 pos) {
    int x = (pos.x) % 2;
    int y = (pos.y) % 2;
    return (y << 1) | x;
}

float IG(ivec2 pos, int stp) {
    return imageLoad(igTexture, pos)[stp];
}


// Green plane interpolation
vec3 interpolateGreen(ivec2 pos) {
    return imageLoad(greenTexture, pos).rgb;
    //return texelFetch(greenTexture, pos, 0).rgb;
}

float ph(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    return mix(g[1],g[0],float(g[0] > EPS)) - c;
}

float pv(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    return mix(g[2],g[0],float(g[0] > EPS)) - c;
}

float pd(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    return mix((g[1]+g[2])/2.0,g[0],float(g[0] > EPS)) - c;
}

float gd(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    if ((g[0] > EPS) && (g[1] > EPS)) {
        return g[0];
    } else {
        return (g[1]+g[2])/2.0;
    }
}

// Green plane enhancement
float enhanceGreen(ivec2 pos) {
    vec3 initialGreen = interpolateGreen(pos);
    int pattern = getBayerPattern(pos);
    float outGr = 0.0;
    float igE = IG(pos,0);
    float igS = IG(pos,1);
    float igW = IG(pos + ivec2(-2,0),0);
    float igN = IG(pos + ivec2(0,-2),1);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);

    float dh = igE + igW + 0.01;
    float dv = igN + igS + 0.01;
    float E = max(dh/dv, dv/dh);
    //return vec2(gd(pos), dir);
    if (pattern == 1 || pattern == 2 || (E >= THRESHOLD)) return gd(pos); // Already green
    //initialGreen[0] = gd(pos);

    // Pass 2
    vec3 D = vec3(0.0);
    for (int dx = -L; dx <= L; dx++) {
        D.x += abs(ph(pos) - ph(pos + ivec2(2*dx, 0)));
        D.y += abs(pv(pos) - pv(pos + ivec2(0, 2*dx)));
        D.z += abs(pd(pos) - pd(pos + ivec2(2*dx, 0))) + abs(pd(pos) - pd(pos + ivec2(0, 2*dx)));
    }
    D.z /= 2.0;
    float gv = initialGreen[2];
    float gh = initialGreen[1];
    //float gd = initialGreen[0];
    float maxD = max(D.x, max(D.y, D.z));
    vec3 mask = vec3(equal(D, vec3(maxD)))+EPS;
    return (gv * mask.x + gh * mask.y + ((gh+gv)/2.0) * mask.z)/(mask.x + mask.y + mask.z);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    pos+=ivec2(0,yOffset);
    // Step 2: Green plane enhancement
    float enhancedGreen = enhanceGreen(pos);
    imageStore(outTexture, pos, vec4(enhancedGreen));
}