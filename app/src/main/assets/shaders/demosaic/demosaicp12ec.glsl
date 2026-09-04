#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform sampler2D bayerTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D igTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
#define alpha 3.75
#define THRESHOLD 1.3
//#define THRESHOLD 0.0
#define EPS 0.0001
#define L 3
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
/*
vec3 interpolateGreen(ivec2 pos) {
    int pattern = getBayerPattern(pos);
    if (pattern == 1 || pattern == 2) return vec3(getBayerSample(pos),getBayerSample(pos),getBayerSample(pos)); // Already green

    float igE = IG(pos,0);
    float igS = IG(pos,1);
    float igW = IG(pos + ivec2(-2,0),0);
    float igN = IG(pos + ivec2(0,-2),1);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);
    // Pass 1
    //float gE = getBayerSample(pos + ivec2(0,-1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    //float gW = getBayerSample(pos + ivec2(0,1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    //float gN = getBayerSample(pos + ivec2(1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
    //float gS = getBayerSample(pos + ivec2(-1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;

    float gE = getBayerSample(pos + ivec2(1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;
    float gW = getBayerSample(pos + ivec2(-1,0))+ (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
    float gN = getBayerSample(pos + ivec2(0,-1))+ (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    float gS = getBayerSample(pos + ivec2(0,1))+ (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    float gh = (gE * wE + gW * wW)/(wE + wW);
    float gv = (gN * wN + gS * wS)/(wN + wS);
    float gd = (gE * wE + gW * wW + gN * wN + gS * wS)/(wE + wW + wN + wS);
    //float gh = (gE + gW)/2.0;
    //float gv = (gN + gS)/2.0;


    float dh = igE + igW + EPS;
    float dv = igN + igS + EPS;

    float E = max(dh/dv, dv/dh);

    if (E < THRESHOLD) {
        return vec3(0.0, gh, gv);
    } else {
        if (dh > dv) {
            return vec3(gv, gh, gv);
        } else {
            return vec3(gh, gh, gv);
        }
    }

    return vec3(0.0);
}*/

vec3 interpolateGreen(ivec2 pos) {
    int pattern = getBayerPattern(pos);
    if (pattern == 1 || pattern == 2) return vec3(getBayerSample(pos),getBayerSample(pos),getBayerSample(pos)); // Already green

    float igE = IG(pos,0);
    float igS = IG(pos,1);
    float igW = IG(pos + ivec2(-2,0),0);
    float igN = IG(pos + ivec2(0,-2),1);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);
    // Pass 1
    //float gE = getBayerSample(pos + ivec2(0,-1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    //float gW = getBayerSample(pos + ivec2(0,1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    //float gN = getBayerSample(pos + ivec2(1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
    //float gS = getBayerSample(pos + ivec2(-1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;

    float gE = getBayerSample(pos + ivec2(1,0));// + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;
    float gW = getBayerSample(pos + ivec2(-1,0));//+ (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
    float gN = getBayerSample(pos + ivec2(0,-1));//+ (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    float gS = getBayerSample(pos + ivec2(0,1));//+ (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    //float gh = (gE * wE + gW * wW)/(wE + wW);
    //float gv = (gN * wN + gS * wS)/(wN + wS);
    float gd = (gE * wE + gW * wW + gN * wN + gS * wS)/(wE + wW + wN + wS);
    float gh = (gE + gW)/2.0;
    float gv = (gN + gS)/2.0;


    float dh = igE + igW + EPS;
    float dv = igN + igS + EPS;

    float E = max(dh/dv, dv/dh);

    if (E < THRESHOLD) {
        return vec3(0.0, gh, gv);
    } else {
        if (dh > dv) {
            return vec3(gv, gh, gv);
        } else {
            return vec3(gh, gh, gv);
        }
    }

    return vec3(0.0, gh, gv);
}

void main() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    pos+=ivec2(0,yOffset);

    // Step 1: Green plane interpolation
    vec3 initialGreen = interpolateGreen(pos);
    //Output = initialGreen;
    imageStore(outTexture, pos, vec4(initialGreen, 1.0));
}