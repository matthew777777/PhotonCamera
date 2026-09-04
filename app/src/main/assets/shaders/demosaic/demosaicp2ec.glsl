#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
//uniform sampler2D RawBuffer;
//uniform sampler2D GreenBuffer;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D greenTexture;
layout(rgba16f, binding = 2) uniform highp readonly image2D igTexture;
layout(rgba16f, binding = 3) uniform highp writeonly image2D outTexture;
uniform int yOffset;
uniform vec4 neutral;
//out vec3 Output;
//#define EPS 0.0001
//#define EPS2 0.001
#define EPS 1e-6
#define EPS2 1e-3
#define alpha 3.75
#define NOISEO 0.0
#define NOISES 0.0
//#define BETA 0.42
#define BETA 0.0
//#define THRESHOLD 1.9
#define THRESHOLD 1.9

#define L 3
//#define greenmin (0.08)
#define greenmin (0.00001)
//#define greenmax (0.9)
#define greenmax (0.99999)
shared float sharedDtcv[12][12]; // Shared memory for dtcv values within the workgroup

// Helper function to determine Bayer pattern at a given position
// 0: R, 1: G (at red row), 2: G (at blue row), 3: B
int getBayerPattern(ivec2 pos) {
    int x = (pos.x) % 2;
    int y = (pos.y) % 2;
    return (y << 1) | x;
}

float getBayerSample(ivec2 pos) {
    return imageLoad(inTexture, pos).r/neutral[getBayerPattern(pos)] + EPS2;
    //return float(texelFetch(RawBuffer, pos, 0).x);
}

vec2 gr(ivec2 pos){
    return imageLoad(greenTexture, pos).rg/neutral[1] + EPS2;
    //return texelFetch(GreenBuffer, pos, 0).xy;
}

float bayer(ivec2 pos){
    return getBayerSample(pos);
    //return float(texelFetch(RawBuffer, pos, 0).x);
}

float dgr(ivec2 pos){
    return gr(pos).x - bayer(pos);
}


float dxy(ivec2 pos, ivec2 stp) {
    return abs((4.0 * getBayerSample(pos) - 3.0 * getBayerSample(pos + stp) - 3.0 * getBayerSample(pos - stp) + getBayerSample(pos - 2*stp) + getBayerSample(pos + 2*stp))/6.0);
}

float dt(ivec2 pos, ivec2 stp) {
    float c = dxy(pos, stp);
    float c2 = dxy(pos + 2*stp, stp);
    float c1 = dxy(pos + stp, stp);
    return (abs(c - c1) + abs(c1 - c2))/2.0;
}

float dxy2(ivec2 pos, ivec2 stp) {
    float c = getBayerSample(pos);
    /*if (direction == 0){
        stp = ivec2(1,0);
    } else {
        stp = ivec2(0,1);
    }*/
    return (abs(getBayerSample(pos) - getBayerSample(pos + 2*stp)) + abs(getBayerSample(pos - stp) - getBayerSample(pos + stp))) / 2.0 + alpha * dt(pos,stp);
}

float IG(ivec2 pos, int stp) {
    //int pattern = getBayerPattern(pos);
    //float useGreen = (pattern == 1 || pattern == 2) ? -1.0 : 1.0;
    //float useInv = 1.0 - useGreen;
    /*if (direction == 0) {
        return 2.0 * dxy2(pos,0) + dxy2(pos + ivec2(0,-1),0) + dxy2(pos + ivec2(0,1),0);
    } else {
        return 2.0 * dxy2(pos,1) + dxy2(pos + ivec2(-1,0),1) + dxy2(pos + ivec2(1,0),1);
    }*/
    return imageLoad(igTexture, pos)[stp];
    //ivec2 invStep = ivec2(1,1)-stp;
    //return 2.0 * dxy2(pos,stp) + dxy2(pos - invStep,stp) + dxy2(pos + invStep,stp);
}

float dl(ivec2 pos){
    //return max(getBayerSample(pos),EPS2) / max(gr(pos).x,EPS2);
    return getBayerSample(pos) - gr(pos).x;
    //return max(gr(pos).x,EPS2)-max(getBayerSample(pos),EPS2);
}

float estimateD(ivec2 pos){
    float igE = IG(pos + ivec2(0, 0), 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos + ivec2(0, 0), 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);
    float dh = igE + igW + EPS2;
    float dv = igN + igS + EPS2;
    float E = max(dh/dv, dv/dh);
    float c = dl(pos);
    ivec2 xs = ivec2(2,0);
    ivec2 ys = ivec2(0,2);
    float v0 = dl(pos + xs);
    float v1 = dl(pos - xs);
    float v2 = dl(pos + ys);
    float v3 = dl(pos - ys);
    float res0 = (wE * v0 + wW * v1 + wS * v2 + wN * v3)/(wE + wW + wS + wN);
    float resx = (wE * v0 + wW * v1)/(wE + wW);
    float resy = (wS * v2 + wN * v3)/(wS + wN);
    //return res0;
    //return dl(pos);

    /*if (dh > dv){
        //return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)) + (wS + wN)*dl(pos))/(2.0*wS + 2.0*wN);
        return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wS + wN);
    } else {
        //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + (wE + wW)*dl(pos))/(2.0*wE + 2.0*wW);
        return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)))/(wE + wW);
    }*/

    float w0 = 1.0-abs(v0 - res0);
    float w1 = 1.0-abs(v1 - res0);
    float w2 = 1.0-abs(v2 - res0);
    float w3 = 1.0-abs(v3 - res0);
    float w4 = 1.0-abs(res0 - res0);
    float w5 = 1.0-abs(resx - res0);
    float w6 = 1.0-abs(resy - res0);
    //float wm = min(min(min(min(w0,w1),w2),w3),min(w5,w6))*0.999;
    float wm = min(min(min(w0,w1),w2),w3)*0.999;
    w0 -= wm;
    w1 -= wm;
    w2 -= wm;
    w3 -= wm;
    w5 -= wm;
    w6 -= wm;
    //float samp = getBayerSample(pos);
    //float noise = sqrt(samp*NOISES + NOISEO);
    float res = (w0*v0 + w1*v1 + w2*v2 + w3*v3)/(w0+w1+w2+w3);
    //if (E > THRESHOLD){
        //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wE + wW + wS + wN);
        //    return mix(dl(pos), res, BETA);
    //    return dl(pos);
    //}
    //return mix(res,dl(pos), clamp((E-1.0)*1.0,0.0,1.0));
    return res;

    //return res;
    //return dl(pos);
    /*float dir = float(gr(pos).y);
    float dte = 0.0;
    if (dir < 0.5){
        return dl(pos);
    } else {
        if (dir > 0.5){
            return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)))/(wE + wW);
        } else {
            return (wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wS + wN);
        }
    }*/
    /*float cin = dl(pos);
    float cc1 = dl(pos + ivec2(2, 0));
    float cc2 = dl(pos + ivec2(-2, 0));
    float cc3 = dl(pos + ivec2(0, 2));
    float cc4 = dl(pos + ivec2(0, -2));
    float sigY = 0.1;
    float d1 = (abs(cc1-cin));
    float d2 = (abs(cc2-cin));
    float d3 = (abs(cc3-cin));
    float d4 = (abs(cc4-cin));
    float w1 = (1.0-d1*d1/(d1*d1 + sigY));
    float w2 = (1.0-d2*d2/(d2*d2 + sigY));
    float w3 = (1.0-d3*d3/(d3*d3 + sigY));
    float w4 = (1.0-d4*d4/(d4*d4 + sigY));
    float wm = min(min(min(w1,w2),w3),w4)*0.99;
    w1 -= wm;
    w2 -= wm;
    w3 -= wm;
    w4 -= wm;*/

    //return (wE * w1*cc1 + wW * w2*cc2 + wS * w3*cc3 + wN * w4*cc4)/(wE * w1+wW * w2+wS * w3+ wN * w4);


    //return (wE * dl(pos + ivec2(2, 0)) + wW * dl(pos + ivec2(-2, 0)) + wS * dl(pos + ivec2(0, 2)) + wN * dl(pos + ivec2(0, -2)))/(wE + wW + wS + wN);
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
    //return 0.0;
}

float dtcv(ivec2 pos){
    //return dl(pos);
    return estimateD(pos);
    //return mix(estimateD(pos), dl(pos), BETA);
    //float diff = (dl(pos) - estimateD(pos));
    //return mix(dl(pos), estimateD(pos), exp(-diff*diff*10.0));
}

// Helper function to fetch dtcv from shared memory if available
float getDtcv(ivec2 pos) {
    ivec2 workgroupStart = ivec2(gl_WorkGroupID.xy) * ivec2(gl_WorkGroupSize.xy);
    //ivec2 workgroupEnd = workgroupStart + ivec2(gl_WorkGroupSize.xy);
    //if (all(greaterThanEqual(pos, workgroupStart)) && all(lessThan(pos, workgroupEnd))) {
        ivec2 localPos = pos - workgroupStart + ivec2(2, 2);
        return sharedDtcv[localPos.x][localPos.y];
    //} else {
        // Fallback to global computation if outside the workgroup
        //return dtcv(pos);
    //    return dl(pos);
    //}
}

float dhtd(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    float wNW = 1.0 / (igN + igW + EPS);
    float wNE = 1.0 / (igN + igE + EPS);
    float wSE = 1.0 / (igS + igE + EPS);
    float wSW = 1.0 / (igS + igW + EPS);

    return (wNW*getDtcv(pos+ivec2(-1,-1))+wNE*getDtcv(pos+ivec2(1,-1))+wSE*getDtcv(pos+ivec2(1,1))+wSW*getDtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
}

float dhtg0(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    ivec2 stp = ivec2(1,0);
    ivec2 invStp = ivec2(0,1);

    return (wE*dhtd(pos+stp)+wW*dhtd(pos-stp)+wS*getDtcv(pos+invStp)+wN*getDtcv(pos-invStp))/(wE + wW + wS + wN);
}

float dhtg1(ivec2 pos){
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);

    ivec2 stp = ivec2(1,0);
    ivec2 invStp = ivec2(0,1);

    return (wE*getDtcv(pos+stp)+wW*getDtcv(pos-stp)+wS*dhtd(pos+invStp)+wN*dhtd(pos-invStp))/(wE + wW + wS + wN);
}


void main() {
    //ivec2 pos = ivec2(gl_FragCoord.xy);
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 localPos = ivec2(gl_LocalInvocationID.xy);
    ivec2 workgroupStart = ivec2(gl_WorkGroupID.xy) * ivec2(gl_WorkGroupSize.xy);
    int fact1 = pos.x%2;
    int fact2 = pos.y%2;

    // Compute and store dtcv in shared memory
    int l = localPos.x + localPos.y * int(gl_WorkGroupSize.x);
    int l1 = min(l*3, 12*12-2);
    int l2 = l1 + 1;
    int l3 = l1 + 2;
    ivec2 shared1 = ivec2(l1 % 12, l1 / 12);
    ivec2 shared2 = ivec2(l2 % 12, l2 / 12);
    ivec2 shared3 = ivec2(l3 % 12, l3 / 12);
    sharedDtcv[shared1.x][shared1.y] = dtcv(workgroupStart + shared1 - ivec2(2,2));
    sharedDtcv[shared2.x][shared2.y] = dtcv(workgroupStart + shared2 - ivec2(2,2));
    sharedDtcv[shared3.x][shared3.y] = dtcv(workgroupStart + shared3 - ivec2(2,2));

    // Ensure all threads have stored their dtcv values
    barrier();
    float minC = 1.0;
    float maxC = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            float c = getBayerSample(pos + ivec2(dx, dy));
            minC = min(minC, c);
            maxC = max(maxC, c);
        }
    }

    float dtc = getDtcv(pos);
    //float dtc = dl(pos);
    vec3 outp;
    float igE = IG(pos, 0);
    float igW = IG(pos + ivec2(-2, 0), 0);
    float igS = IG(pos, 1);
    float igN = IG(pos + ivec2(0, -2), 1);

    float wE = 1.0 / (igE + EPS);
    float wS = 1.0 / (igS + EPS);
    float wW = 1.0 / (igW + EPS);
    float wN = 1.0 / (igN + EPS);
    bool skip = false;
    float minCol = 1.0;
    float maxCol = 0.0;

    if(fact1 ==0 && fact2 == 0) {//rggb
        //outp.g = gr(pos).x;
        outp.r = getBayerSample(pos);
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //outp.g = getBayerSample(pos) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = gr(pos).x;
        //outp.b = interpolateColor(pos);
        //outp.b = grk * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(1,-1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
        //outp.b = grk * dhtd(pos);
        if (skip) {
            outp.g = grk;
            outp.b = (getBayerSample(pos+ivec2(1,1)) + getBayerSample(pos+ivec2(-1,-1)) + getBayerSample(pos+ivec2(1,-1)) + getBayerSample(pos+ivec2(-1,1)))/4.0;
        } else {
            outp.g = getBayerSample(pos) - dtc;
            outp.b = grk + dhtd(pos);
        }
    } else
    if(fact1 ==1 && fact2 == 0) {//grbg
        outp.g = gr(pos).x;
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = outp.g;
        //outp.r = grk * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        //outp.r = grk *  dhtg1(pos);
        //outp.b = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.b = grk * dhtg0(pos);
        if (skip){
            outp.r = (getBayerSample(pos+ivec2(1,0)) + getBayerSample(pos+ivec2(-1,0)))/2.0;
            outp.b = (getBayerSample(pos+ivec2(0,1)) + getBayerSample(pos+ivec2(0,-1)))/2.0;
        } else {
            outp.b = grk + dhtg0(pos);
            outp.r = grk + dhtg1(pos);
        }
        //outp.r = grk * (wE*dtcv(pos+ivec2(1,0))+wW*dtcv(pos+ivec2(-1,0))+wS*dhtd(pos+ivec2(0,1))+wN*dhtd(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
    } else
    if(fact1 ==0 && fact2 == 1) {//gbrg
        outp.g = gr(pos).x;
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = outp.g;
        //outp.b = grk * (dtcv(pos+ivec2(1,0))+dtcv(pos+ivec2(-1,0)))/2.0;
        //outp.b = grk *  dhtg1(pos);
        //outp.r = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.r = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.r = grk *  dhtg0(pos);
        //outp.b = grk * (wE*dtcv(pos+ivec2(1,0))+wW*dtcv(pos+ivec2(-1,0))+wS*dhtd(pos+ivec2(0,1))+wN*dhtd(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (wE*dhtd(pos+ivec2(1,0))+wW*dhtd(pos+ivec2(-1,0))+wS*dtcv(pos+ivec2(0,1))+wN*dtcv(pos+ivec2(0,-1)))/(wE + wW + wS + wN);
        //outp.b = grk * (dtcv(pos+ivec2(0,1))+dtcv(pos+ivec2(0,-1)))/2.0;
        //outp.b = grk * dhtg0(pos);
        if (skip){
            outp.b = (getBayerSample(pos+ivec2(1,0)) + getBayerSample(pos+ivec2(-1,0)))/2.0;
            outp.r = (getBayerSample(pos+ivec2(0,1)) + getBayerSample(pos+ivec2(0,-1)))/2.0;
        } else {
            outp.r = grk + dhtg0(pos);
            outp.b = grk + dhtg1(pos);
        }
    } else  {//bggr
        //outp.g = gr(pos).x;
        outp.b = getBayerSample(pos);
        //outp.g = max(getBayerSample(pos),EPS2) / dtc;
        //outp.g = getBayerSample(pos) / dtc;
        //float grk = max(outp.g,EPS2);
        float grk = gr(pos).x;
        //outp.r = interpolateColor(pos);
        //outp.r = grk * (wNW*dtcv(pos+ivec2(-1,-1))+wNE*dtcv(pos+ivec2(1,-1))+wSE*dtcv(pos+ivec2(1,1))+wSW*dtcv(pos+ivec2(-1,1)))/(wNW + wNE + wSE + wSW);
        if (skip){
            outp.g = grk;
            outp.r = (getBayerSample(pos+ivec2(1,1)) + getBayerSample(pos+ivec2(-1,-1)) + getBayerSample(pos+ivec2(1,-1)) + getBayerSample(pos+ivec2(-1,1)))/4.0;
        } else {
            outp.g = getBayerSample(pos) - dtc;
            outp.r = grk + dhtd(pos);
        }
        //outp.r = grk * dhtd(pos);
    }
    //outp.rb = vec2(outp.g);
    //outp.rg = vec2((igE+igW)/2.0, (igS+igN)/2.0);
    //outp.b = 0.0;
    float brCorr = gr(pos).x/outp.g;
    //float sat = max(max(outp.r,outp.g),outp.b)-min(min(outp.r,outp.g),outp.b);
    float sat = maxC-minC;
    //brCorr = mix(brCorr,1.0,clamp(brCorr*10.0,0.0,1.0));
    brCorr = mix(1.0,brCorr,clamp(sat*4.0,0.0,1.0));
    outp = clamp((outp-EPS2)*neutral.rga,0.0,1.0);
    //outp.rb = vec2(igE, igS);
    imageStore(outTexture, pos, vec4(outp, 1.0));
    //imageStore(outTexture, pos, vec4(gr(pos).x));
}

/*
void main() {
    //ivec2 pos = ivec2(gl_FragCoord.xy);
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    ivec2 localPos = ivec2(gl_LocalInvocationID.xy);
    ivec2 workgroupStart = ivec2(gl_WorkGroupID.xy) * ivec2(gl_WorkGroupSize.xy);
    int fact1 = pos.x%2;
    int fact2 = pos.y%2;

    imageStore(outTexture, pos, vec4(gr(pos).r));
}*/