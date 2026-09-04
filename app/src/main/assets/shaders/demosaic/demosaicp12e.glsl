precision highp float;
precision highp sampler2D;
uniform sampler2D bayerTexture;
#define alpha 3.75
//#define THRESHOLD 1.9
#define THRESHOLD 0.0
#define EPS 0.0001
#define L 3
out vec3 Output;
uniform int yOffset;

// Helper function to get Bayer sample
float getBayerSample(ivec2 pos) {
    return texelFetch(bayerTexture, pos, 0).r;
}

// Helper function to determine Bayer pattern at a given position
// 0: R, 1: G (at red row), 2: G (at blue row), 3: B
int getBayerPattern(ivec2 pos) {
    int x = (pos.x) % 2;
    int y = (pos.y) % 2;
    return (y << 1) | x;
}

float dxy(ivec2 pos, int direction) {
    int pattern = getBayerPattern(pos);
    float useGreen = (pattern == 1 || pattern == 2) ? 1.0 : -1.0;
    if (direction == 0) {
        return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(1,0)) - 3.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(-2,0)) + getBayerSample(pos + ivec2(2,0)))/6.0);
        //return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(1,0)) - getBayerSample(pos + ivec2(-1,0)))/2.0;
    } else {
        return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(0,1)) - 3.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,-2)) + getBayerSample(pos + ivec2(0,2)))/6.0);
        //return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(0,1)) - getBayerSample(pos + ivec2(0,-1)))/2.0;
    }
}

float dt(ivec2 pos, int direction) {
    float c = dxy(pos, direction);
    if (direction == 0) {
        float c2 = dxy(pos + ivec2(2, 0), direction);
        float c1 = dxy(pos + ivec2(1, 0), direction);
        return (abs(c - c1) + abs(c1 - c2))/2.0;
    } else {
        float c2 = dxy(pos + ivec2(0, 2), direction);
        float c1 = dxy(pos + ivec2(0, 1), direction);
        return (abs(c - c1) + abs(c1 - c2))/2.0;
    }
}

float dxy2(ivec2 pos, int direction) {
    float c = getBayerSample(pos);
    float c1;
    float c2;
    float c3;
    if (direction == 0){
        c1 = getBayerSample(pos + ivec2(2,0));
        c2 = getBayerSample(pos + ivec2(-1,0));
        c3 = getBayerSample(pos + ivec2(1,0));
    } else {
        c1 = getBayerSample(pos + ivec2(0,2));
        c2 = getBayerSample(pos + ivec2(0,-1));
        c3 = getBayerSample(pos + ivec2(0,1));
    }
    return (abs(c - c1) + abs(c2 - c3)) / 2.0 + alpha * dt(pos,direction);
}

float IG(ivec2 pos, int direction) {
    int pattern = getBayerPattern(pos);
    float useGreen = (pattern == 1 || pattern == 2) ? -1.0 : 1.0;
    float useInv = 1.0 - useGreen;
    if (direction == 0) {
        return 2.0 * dxy2(pos,0) + dxy2(pos + ivec2(0,-1),0) + dxy2(pos + ivec2(0,1),0);
    } else {
        return 2.0 * dxy2(pos,1) + dxy2(pos + ivec2(-1,0),1) + dxy2(pos + ivec2(1,0),1);
    }
}


// Green plane interpolation
vec3 interpolateGreen(ivec2 pos) {
    int pattern = getBayerPattern(pos);
    if (pattern == 1 || pattern == 2) return vec3(getBayerSample(pos),getBayerSample(pos),getBayerSample(pos)); // Already green

    float igE = IG(pos,1);
    float igS = IG(pos,0);
    float igW = IG(pos + ivec2(2,0),1);
    float igN = IG(pos + ivec2(0,2),0);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);
    // Pass 1
    //float gE = getBayerSample(pos + ivec2(0,-1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    //float gW = getBayerSample(pos + ivec2(0,1)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    //float gN = getBayerSample(pos + ivec2(1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
    //float gS = getBayerSample(pos + ivec2(-1,0)) + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;

    float gE = getBayerSample(pos + ivec2(0,-1));// + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,-2)))/2.0 + (getBayerSample(pos + ivec2(0,-3)) - 2.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,1)))/8.0;
    float gW = getBayerSample(pos + ivec2(0,1));// + (getBayerSample(pos) - getBayerSample(pos + ivec2(0,2)))/2.0 + (getBayerSample(pos + ivec2(0,-1)) - 2.0 * getBayerSample(pos + ivec2(0,1)) + getBayerSample(pos + ivec2(0,3)))/8.0;
    float gN = getBayerSample(pos + ivec2(1,0));// + (getBayerSample(pos) - getBayerSample(pos + ivec2(2,0)))/2.0 + (getBayerSample(pos + ivec2(-1,0)) - 2.0 * getBayerSample(pos + ivec2(1,0)) + getBayerSample(pos + ivec2(3,0)))/8.0;
    float gS = getBayerSample(pos + ivec2(-1,0));// + (getBayerSample(pos) - getBayerSample(pos + ivec2(-2,0)))/2.0 + (getBayerSample(pos + ivec2(-3,0)) - 2.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(1,0)))/8.0;
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
    /*if (dh > dv) {
        return vec3(gv, gh, gv);
        //return vec3(gv, gh, gv);
    } else {
        return vec3(gh, gh, gv);
        //return vec3(gh, gh, gv);
    }*/

    return vec3(0.0);
}


void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    pos+=ivec2(0,yOffset);

    // Step 1: Green plane interpolation
    vec3 initialGreen = interpolateGreen(pos);
    Output = initialGreen;
}