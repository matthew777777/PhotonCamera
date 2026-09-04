precision highp float;
precision highp sampler2D;
uniform sampler2D RawBuffer;
uniform sampler2D GreenBuffer;
uniform int yOffset;
out vec3 Output;

#define EPS 0.0001
#define EPS2 0.001
#define alpha 3.75
#define BETA 0.42
//#define BETA 0.0
#define THRESHOLD 1.9
#define L 3
//#define greenmin (0.04)
#define greenmin (0.01)
//#define greenmax (0.9)
#define greenmax (0.99)
// Helper function to determine Bayer pattern at a given position
// 0: R, 1: G (at red row), 2: G (at blue row), 3: B
int getBayerPattern(ivec2 pos) {
    int x = (pos.x) % 2;
    int y = (pos.y) % 2;
    return (y << 1) | x;
}

float getBayerSample(ivec2 pos) {
    return float(texelFetch(RawBuffer, pos, 0).x);
}

vec2 gr(ivec2 pos){
    return texelFetch(GreenBuffer, pos, 0).xy;
}

float bayer(ivec2 pos){
    return float(texelFetch(RawBuffer, pos, 0).x);
}

float dgr(ivec2 pos){
    return gr(pos).x - bayer(pos);
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

/*
float interpolateColor(in ivec2 coords){
    bool usegreen = true;
    float green[5];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,-1))));
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(1,-1))));
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,1))));
    green[3] = float(texelFetch(GreenBuffer, (coords+ivec2(1,1))));
    green[4] = float(texelFetch(GreenBuffer, (coords),              0));
    for(int i = 0; i<5; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[4];
        coeff[0] = float(getBayerSample((coords+ivec2(-1,-1))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(1,-1))))/(green[1]);
        coeff[2] = float(getBayerSample((coords+ivec2(-1,1))))/(green[2]);
        coeff[3] = float(getBayerSample((coords+ivec2(1,1))))/(green[3]);
        return (green[4]*(coeff[0]+coeff[1]+coeff[2]+coeff[3])/4.);
    } else {
        return ((float(getBayerSample((coords+ivec2(-1,-1))))+float(getBayerSample((coords+ivec2(1,-1))))
        +float(getBayerSample((coords+ivec2(-1,1))))+float(getBayerSample((coords+ivec2(1,1)))))/(4.));
        }
}
float interpolateColorx(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(-1,0))));
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(0,0))));
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(1,0))));
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(getBayerSample((coords+ivec2(-1,0))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(1,0))))/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(getBayerSample((coords+ivec2(-1,0))))+float(getBayerSample((coords+ivec2(1,0)))))/(2.));
    }
}
float interpolateColory(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(texelFetch(GreenBuffer, (coords+ivec2(0,-1))));
    green[1] = float(texelFetch(GreenBuffer, (coords+ivec2(0,0))));
    green[2] = float(texelFetch(GreenBuffer, (coords+ivec2(0,1))));
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(getBayerSample((coords+ivec2(0,-1))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(0,1))))/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(getBayerSample((coords+ivec2(0,-1))))+float(getBayerSample((coords+ivec2(0,1)))))/(2.));
    }
}
*/

float interpolateColor(in ivec2 coords){
    bool usegreen = true;
    float green[5];
    float igE = IG(coords, 1);
    float igS = IG(coords, 0);
    float igW = IG(coords + ivec2(2, 0), 1);
    float igN = IG(coords + ivec2(0, 2), 0);

    float wNW = 1.0 / (igN + igW + 0.0001);
    float wNE = 1.0 / (igN + igE + 0.0001);
    float wSE = 1.0 / (igS + igE + 0.0001);
    float wSW = 1.0 / (igS + igW + 0.0001);

    green[0] = float(gr((coords+ivec2(-1,-1))).x);
    green[1] = float(gr((coords+ivec2(1,-1))).x);
    green[2] = float(gr((coords+ivec2(-1,1))).x);
    green[3] = float(gr((coords+ivec2(1,1))).x);
    green[4] = float(gr((coords)).x);
    for(int i = 0; i<5; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[4];
        coeff[0] = float(getBayerSample((coords+ivec2(-1,-1))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(1,-1))))/(green[1]);
        coeff[2] = float(getBayerSample((coords+ivec2(-1,1))))/(green[2]);
        coeff[3] = float(getBayerSample((coords+ivec2(1,1))))/(green[3]);
        //return (green[4]+(coeff[0]+coeff[1]+coeff[2]+coeff[3])/4.);
        //return (green[4]+(wNW*coeff[0]+wNE*coeff[1]+wSE*coeff[2]+wSW*coeff[3])/(wNW + wNE + wSE + wSW));
        return (green[4]*(wNW*coeff[0]+wSW*coeff[1]+wNE*coeff[2]+wSE*coeff[3])/(wNW + wNE + wSE + wSW));
    } else {
        return ((float(getBayerSample((coords+ivec2(-1,-1))))+float(getBayerSample((coords+ivec2(1,-1))))
        +float(getBayerSample((coords+ivec2(-1,1))))+float(getBayerSample((coords+ivec2(1,1)))))/(4.));
    }
}

float interpolateColorx(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(gr((coords+ivec2(-1,0))).x);
    green[1] = float(gr((coords+ivec2(0,0))).x);
    green[2] = float(gr((coords+ivec2(1,0))).x);
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(getBayerSample((coords+ivec2(-1,0))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(1,0))))/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(getBayerSample((coords+ivec2(-1,0))))+float(getBayerSample((coords+ivec2(1,0)))))/(2.));
    }
}
float interpolateColory(in ivec2 coords){
    bool usegreen = true;
    float green[3];
    green[0] = float(gr((coords+ivec2(0,-1))).x);
    green[1] = float(gr((coords+ivec2(0,0))).x);
    green[2] = float(gr((coords+ivec2(0,1))).x);
    for(int i = 0; i<3; i++)if(green[i] < greenmin || green[i] > greenmax) usegreen = false;
    if(usegreen){
        float coeff[2];
        coeff[0] = float(getBayerSample((coords+ivec2(0,-1))))/(green[0]);
        coeff[1] = float(getBayerSample((coords+ivec2(0,1))))/(green[2]);
        return (green[1]*(coeff[0]+coeff[1])/2.);
    } else {
        return ((float(getBayerSample((coords+ivec2(0,-1))))+float(getBayerSample((coords+ivec2(0,1)))))/(2.));
    }
}

float dl(ivec2 pos){
    return getBayerSample(pos) / max(gr(pos).x,EPS2);
}

float estimateD(ivec2 pos){
    float igE = IG(pos, 1);
    float igS = IG(pos, 0);
    float igW = IG(pos + ivec2(2, 0), 1);
    float igN = IG(pos + ivec2(0, 2), 0);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);
    float dh = igE + igW + 0.01;
    float dv = igN + igS + 0.01;
    float E = max(dh/dv, dv/dh);
    if (E < THRESHOLD){
        return (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)) + wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wE + wW + wS + wN);
    }
    if (dh > dv){
        return (wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wS + wN);
    } else {
        return (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)))/(wE + wW);
    }
    //float dir = float(gr(pos).y);
    //float dir = 0.0;
    /*float dte = 0.0;
    if (dir < 0.5){
        dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)))/(wE + wW);
    } else {
        if (dir > 0.5){
            dte = (wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wS + wN);
        } else {
            dte = (wE * dl(pos + ivec2(0, 2)) + wW * dl(pos + ivec2(0, -2)) + wS * dl(pos + ivec2(2, 0)) + wN * dl(pos + ivec2(-2, 0)))/(wE + wW + wS + wN);
        }
    }*/
    return 0.0;
}

float dtcv(ivec2 pos){
    return mix(estimateD(pos), dl(pos), BETA);
}

void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    int fact1 = pos.x%2;
    int fact2 = pos.y%2;
    float igE = IG(pos, 1);
    float igS = IG(pos, 0);
    float igW = IG(pos + ivec2(2, 0), 1);
    float igN = IG(pos + ivec2(0, 2), 0);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    float wNW = 1.0 / (igN + igW + EPS);
    float wNE = 1.0 / (igN + igE + EPS);
    float wSE = 1.0 / (igS + igE + EPS);
    float wSW = 1.0 / (igS + igW + EPS);
    float dtc = max(dtcv(pos),EPS2);
    if(fact1 ==0 && fact2 == 0) {//rggb
        //Output.g = gr(pos).x;
        Output.r = float(getBayerSample(pos));
        Output.g = getBayerSample(pos) / dtc;
        //Output.b = interpolateColor(pos);
        Output.b = Output.g * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(-1,1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(1,-1)))/(wNW + wNE + wSE + wSW);
    } else
    if(fact1 ==1 && fact2 == 0) {//grbg
        Output.g = gr(pos).x;
        Output.r = Output.g * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        Output.b = Output.g * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //Output.r = interpolateColorx(pos);
        //Output.b = interpolateColory(pos);
    } else
    if(fact1 ==0 && fact2 == 1) {//gbrg
        Output.g = gr(pos).x;
        Output.b = Output.g * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        Output.r = Output.g * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //Output.b = interpolateColorx(pos);
        //Output.r = interpolateColory(pos);
    } else  {//bggr
        //Output.g = gr(pos).x;
        Output.b = float(getBayerSample(pos));
        Output.g = getBayerSample(pos) / dtc;
        //Output.r = interpolateColor(pos);
        Output.r = Output.g * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(-1,1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(1,-1)))/(wNW + wNE + wSE + wSW);
    }
    //Output.rb = vec2(Output.g);
    //Output.rg = vec2((igE+igW)/2.0, (igS+igN)/2.0);
    //Output.b = 0.0;
    Output = clamp(Output,0.0,1.0);
}