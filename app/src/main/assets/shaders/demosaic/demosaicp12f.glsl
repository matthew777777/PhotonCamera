precision highp float;
precision highp sampler2D;
uniform sampler2D bayerTexture;
uniform sampler2D greenTexture;
#define alpha 3.75
#define L 7
#define THRESHOLD 1.9
out vec2 Output;
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
        //return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(1,0)) - 3.0 * getBayerSample(pos + ivec2(-1,0)) + getBayerSample(pos + ivec2(-2,0)) + getBayerSample(pos + ivec2(2,0)))/6.0);
        return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(1,0)) - getBayerSample(pos + ivec2(-1,0)))/2.0;
    } else {
        //return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + ivec2(0,1)) - 3.0 * getBayerSample(pos + ivec2(0,-1)) + getBayerSample(pos + ivec2(0,-2)) + getBayerSample(pos + ivec2(0,2)))/6.0);
        return (2.0 * getBayerSample(pos) - getBayerSample(pos + ivec2(0,1)) - getBayerSample(pos + ivec2(0,-1)))/2.0;
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
    return texelFetch(greenTexture, pos, 0).rgb;
}

float ph(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    if (g[0] > 0.0) {
        return g[0] - c;
    }
    return g[1] - c;
}

float pv(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    if ((g[0] > 0.0) && (g[1] > 0.0)) {
        return g[0] - c;
    }
    return g[2] - c;
}

float pd(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    float c = getBayerSample(pos);
    if ((g[0] > 0.0) && (g[1] > 0.0)) {
        return g[0] - c;
    }
    return (g[1]+g[2])/2.0 - c;
}

float gd(ivec2 pos) {
    vec3 g = interpolateGreen(pos);
    //if ((g[0] > 0.0) && (g[1] > 0.0)) {
    return g[0];
    //if(g[0] == g[1] || g[0] == g[2]){
    //    return g[0];
    //}
    //return (g[1]+g[2])/2.0;
}


// Green plane enhancement
vec2 enhanceGreen(ivec2 pos) {
    vec3 initialGreen = interpolateGreen(pos);
    int pattern = getBayerPattern(pos);
    float igE = IG(pos,1);
    float igS = IG(pos,0);
    float igW = IG(pos + ivec2(2,0),1);
    float igN = IG(pos + ivec2(0,2),0);

    float wE = 1.0 / (igE + 0.0001);
    float wS = 1.0 / (igS + 0.0001);
    float wW = 1.0 / (igW + 0.0001);
    float wN = 1.0 / (igN + 0.0001);

    float dh = igE + igW + 0.01;
    float dv = igN + igS + 0.01;
    float dir = 0.0;
    if (dh > dv) {
        dir = 1.0;
    } else {
        dir = 0.0;
    }
    float E = max(dh/dv, dv/dh);
    //return vec2(gd(pos), dir);
    if (pattern == 1 || pattern == 2 || (E >= THRESHOLD)) return vec2(gd(pos), dir); // Already green

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
    float gd = initialGreen[0];
    if (D.x < D.y && D.x < D.z) {
        initialGreen[0] = gv;
        dir = 0.0;
        //g = gs[1];
    } else if (D.y < D.x && D.y < D.z) {
        initialGreen[0] = gh;
        //initialGreen[0] = 1.0;
        dir = 1.0;
        //g = gs[0];
    } else {
        initialGreen[0] = (gh+gv)/2.0;
        //initialGreen[0] = gd;
        //initialGreen[0] = gd;
        dir = 0.5;
        //g = gs[2];
    }

    return vec2(initialGreen[0], dir);
}

// Red and Blue plane interpolation
/*
vec3 interpolateRedBlue(ivec2 pos, float green) {
    int pattern = getBayerPattern(pos);
    vec3 result;
    result.g = green;

    float igH = computeIG(pos, 0);
    float igV = computeIG(pos, 1);

    if (pattern == 0) { // Red center
                        result.r = getBayerSample(pos);
                        // Interpolate Blue
                        float b1 = green - (getBayerSample(pos + ivec2(-1, -1)) - getBayerSample(pos + ivec2(-1, -1)));
                        float b2 = green - (getBayerSample(pos + ivec2(1, -1)) - getBayerSample(pos + ivec2(1, -1)));
                        float b3 = green - (getBayerSample(pos + ivec2(-1, 1)) - getBayerSample(pos + ivec2(-1, 1)));
                        float b4 = green - (getBayerSample(pos + ivec2(1, 1)) - getBayerSample(pos + ivec2(1, 1)));
                        result.b = (b1 + b2 + b3 + b4) / 4.0;
    } else if (pattern == 3) { // Blue center
                               result.b = getBayerSample(pos);
                               // Interpolate Red
                               float r1 = green - (getBayerSample(pos + ivec2(-1, -1)) - getBayerSample(pos + ivec2(-1, -1)));
                               float r2 = green - (getBayerSample(pos + ivec2(1, -1)) - getBayerSample(pos + ivec2(1, -1)));
                               float r3 = green - (getBayerSample(pos + ivec2(-1, 1)) - getBayerSample(pos + ivec2(-1, 1)));
                               float r4 = green - (getBayerSample(pos + ivec2(1, 1)) - getBayerSample(pos + ivec2(1, 1)));
                               result.r = (r1 + r2 + r3 + r4) / 4.0;
    } else if (pattern == 1) { // Green in red row
                               // Interpolate Red
                               result.r = igH < igV ? (getBayerSample(pos + ivec2(-1, 0)) + getBayerSample(pos + ivec2(1, 0))) / 2.0 :
                               (getBayerSample(pos + ivec2(0, -1)) + getBayerSample(pos + ivec2(0, 1))) / 2.0;
                               // Interpolate Blue
                               result.b = (getBayerSample(pos + ivec2(-1, -1)) + getBayerSample(pos + ivec2(1, -1)) +
                               getBayerSample(pos + ivec2(-1, 1)) + getBayerSample(pos + ivec2(1, 1))) / 4.0;
    } else { // Green in blue row
             // Interpolate Blue
             result.b = igH < igV ? (getBayerSample(pos + ivec2(-1, 0)) + getBayerSample(pos + ivec2(1, 0))) / 2.0 :
             (getBayerSample(pos + ivec2(0, -1)) + getBayerSample(pos + ivec2(0, 1))) / 2.0;
             // Interpolate Red
             result.r = (getBayerSample(pos + ivec2(-1, -1)) + getBayerSample(pos + ivec2(1, -1)) +
             getBayerSample(pos + ivec2(-1, 1)) + getBayerSample(pos + ivec2(1, 1))) / 4.0;
    }

    return result;
}*/

void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    pos+=ivec2(0,yOffset);
    // Step 2: Green plane enhancement
    vec2 enhancedGreen = enhanceGreen(pos);
    //vec3 initialGreen = interpolateGreen(pos);
    // Step 3: Red and Blue plane interpolation
    //Output = vec2(enhancedGreen[0]);
    //Output.x = IG(pos,1);
    //Output.y = IG(pos,0);
    //Output = vec2(gd(pos));
    //Output = vec2(enhancedGreen[0],enhancedGreen[0]);
    Output = enhancedGreen;
}