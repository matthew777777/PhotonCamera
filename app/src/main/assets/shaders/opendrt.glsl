/*
 * OpenDRT v1.1.0-derived SDR transform, Copyright (C) Jed Smith.
 * Source: https://github.com/jedypod/open-display-transform
 * License: GPL-3.0-or-later (this project is GPL-3.0).
 *
 * This mobile port fixes the input/output to scene-linear Rec.709/sRGB D65 and
 * SDR Rec.709 D65 respectively. It implements OpenDRT's Standard look.
 */
precision highp float;
precision highp sampler2D;
#define ODRT_PEAK 100.0
#define ODRT_GREY 10.0
#define ODRT_CONTRAST 1.66
#define ODRT_TOE 0.003
#define ODRT_GREY_BOOST 0.13
#define ODRT_HDR_PURITY 0.5
#define ODRT_POSTLUT 0
#define ODRT_POSTLUT_SIZE 64.0
#define ODRT_POSTLUT_TILES 8.0
#define ODRT_OUTPUT_P3 0
#define ODRT_LOOK 0
#define ODRT_TONESCALE 0
#define PI 3.14159265358979323846
#define SQRT3 1.7320508075688772

uniform sampler2D InputBuffer;
#if ODRT_POSTLUT == 1
uniform sampler2D PostLut;
#endif
out vec3 Output;

const mat3 REC709_TO_XYZ = mat3(0.4123907993,0.2126390059,0.0193308187,
                                0.3575843394,0.7151686788,0.1191947798,
                                0.1804807884,0.0721923154,0.9505321522);
const mat3 XYZ_TO_P3 = mat3(2.4934969119,-0.8294889696,0.0358458302,
                            -0.9313836179,1.7626640603,-0.0761723893,
                            -0.4027107845,0.0236246858,0.9568845240);
const mat3 P3_TO_XYZ = mat3(0.4865709486,0.2289745641,0.0,
                            0.2656676932,0.6917385218,0.0451133819,
                            0.1982172852,0.0792869141,1.0439443689);
const mat3 XYZ_TO_REC709 = mat3(3.2409699419,-0.9692436363,0.0556300797,
                                -1.5373831776,1.8759675015,-0.2039769589,
                                -0.4986107603,0.0415550574,1.0569715142);
const mat3 D65_TO_D75 = mat3(0.9810010791,-0.0084348805,0.0005528096,
                              -0.0116619254,0.9965060949,0.0017984081,
                              0.0265614092,0.0105696544,1.1237472296);
const mat3 D65_TO_D60 = mat3(1.0118224621,0.0056168283,-0.0003357357,
                              0.0077887932,1.0015064478,-0.0010509500,
                              -0.0157783031,-0.0062851757,0.9273666739);
const mat3 D65_TO_D50 = mat3(1.0425740480,0.0221935362,-0.0011648831,
                              0.0308911763,1.0018566847,-0.0034205271,
                              -0.0528126210,-0.0210737623,0.7617890835);

float spow(float x, float p) { return x <= 0.0 ? x : pow(x, p); }
vec3 spow(vec3 x, float p) { return vec3(spow(x.r,p), spow(x.g,p), spow(x.b,p)); }
float toeQuadratic(float x, float toe, bool inverse) {
    if (toe == 0.0) return x;
    return inverse ? (x + sqrt(max(0.0, x * (4.0 * toe + x)))) * 0.5 : x*x/(x + toe);
}
float hyperbolic(float x, float s, float p) { return spow(x / max(x + s, 1e-6), p); }
float softplus(float x, float s) { return x > 10.0*s || s < 1e-4 ? x : s * log(max(0.0, 1.0 + exp(x/s))); }
float gauss(float x, float w) { return exp(-x*x/w); }
float hueOffset(float h, float o) { return mod(h - o + PI, 2.0*PI) - PI; }
vec2 opponent(vec3 c) { return vec2(c.r-c.b, c.g-(c.r+c.b)*0.5); }
float toeCubic(float x, float m, float w, bool inverse) {
    if (m == 1.0) return x;
    float x2 = x*x;
    if (!inverse) return x * (x2 + m*w) / (x2 + w);
    float p0 = x2 - 3.0*m*w;
    float p1 = 2.0*x2 + 27.0*w - 9.0*m*w;
    float p2 = pow(max(sqrt(max(0.0, x2*p1*p1 - 4.0*p0*p0*p0))*0.5 + x*p1*0.5, 1e-8), 1.0/3.0);
    return p0/(3.0*p2) + p2/3.0 + x/3.0;
}
float contrastHigh(float x, float p, float pivot, float stops) {
    float x0 = 0.18 * pow(2.0, pivot);
    if (x < x0 || p == 1.0) return x;
    float o = x0 - x0/p;
    float s0 = pow(x0, 1.0-p)/p;
    float x1 = x0 * pow(2.0, stops);
    float k1 = p*s0*pow(x1,p)/x1;
    float y1 = s0*pow(x1,p) + o;
    return x > x1 ? k1*(x-x1)+y1 : s0*pow(x,p)+o;
}

struct OpenDrtStyle {
    vec4 tone;       // contrast, shoulder, toe, offset
    vec4 lowHigh;    // low enable/amount/width, high enable
    vec4 highRender; // high amount/pivot/stops, rendering-space saturation
    vec3 renderW;
    vec4 purityLow;
    vec4 purityHigh;
    vec3 softClip;
    vec3 midLow;
    vec3 midHigh;
    vec4 brilliance;
    vec2 brillianceShape;
    vec4 postBrilliance;
    vec2 hueContrast;
    vec3 hueRgb;
    vec3 hueRgbRange;
    vec3 hueCmy;
    vec3 hueCmyRange;
    int creativeWhite; // 0 D65, 1 D75, 2 D60, 3 D50
};

OpenDrtStyle openDrtStyle() {
    OpenDrtStyle s;
    // Standard (the upstream default), then replace complete parameter groups
    // for every upstream look preset.
    s.tone=vec4(1.66,.5,.003,.005); s.lowHigh=vec4(0.,0.,.5,0.); s.highRender=vec4(0.,1.,4.,.35); s.renderW=vec3(.25,.20,.55);
    s.purityLow=vec4(.25,.5,0.,.1); s.purityHigh=vec4(.25,.5,0.,1.); s.softClip=vec3(.06,.08,.06); s.midLow=vec3(.4,.25,.5); s.midHigh=vec3(-.8,.35,.4);
    s.brilliance=vec4(0.,-2.5,-1.5,-1.5); s.brillianceShape=vec2(.5,.35); s.postBrilliance=vec4(-.5,-1.25,-1.25,-.25); s.hueContrast=vec2(1.,.3); s.hueRgb=vec3(.6,.35,.66); s.hueRgbRange=vec3(.6,1.,1.); s.hueCmy=vec3(.25,0.,0.); s.hueCmyRange=vec3(1.); s.creativeWhite=0;
    #if ODRT_LOOK == 1
    s.tone=vec4(1.05,.5,.1,.01); s.lowHigh=vec4(1.,1.5,.2,0.); s.purityLow=vec4(.25,.45,0.,.1); s.purityHigh=vec4(.25,.25,0.,1.); s.midLow=vec3(1.,.4,.5); s.midHigh=vec3(-.8,.66,.6); s.postBrilliance=vec4(0.,-1.7,-2.,-.5); s.hueRgbRange.x=.8; s.hueCmy.x=.15;
    #elif ODRT_LOOK == 2
    s.tone=vec4(1.6,.5,.01,.01); s.lowHigh=vec4(1.,.25,.75,0.); s.highRender.w=.25; s.purityLow=vec4(.15,.5,.15,.1); s.purityHigh=vec4(.25,.15,.15,1.); s.softClip=vec3(.05,.08,.05); s.midLow=vec3(.5,.5,.5); s.midHigh=vec3(-.8,.5,.5); s.brilliance=vec4(-1.,-2.,-2.,0.); s.brillianceShape=vec2(.25,.25); s.postBrilliance=vec4(-1.,-.5,-.25,-.25); s.hueContrast.y=.4; s.hueRgb=vec3(.6,.8,.6); s.hueRgbRange=vec3(1.15,1.25,1.); s.hueCmy=vec3(.25,.25,.35); s.hueCmyRange=vec3(.25,.5,.5);
    #elif ODRT_LOOK == 3
    s.tone=vec4(1.5,.5,.003,.003); s.lowHigh=vec4(1.,.4,.5,0.); s.purityLow=vec4(.5,1.,0.,.5); s.purityHigh=vec4(.15,.15,.15,1.); s.softClip=vec3(.05,.06,.05); s.midLow=vec3(.8,.5,.4); s.midHigh=vec3(-.8,.4,.4); s.brilliance=vec4(0.,-1.25,-1.25,-.25); s.brillianceShape=vec2(.3,.5); s.postBrilliance=vec4(-.5,-1.25,-1.25,-.5); s.hueContrast.y=.4; s.hueRgb=vec3(.5,.35,.5); s.hueRgbRange=vec3(.8,1.,1.); s.hueCmy=vec3(.25,0.,.25);
    #elif ODRT_LOOK == 4
    s.tone=vec4(1.15,.5,.04,.006); s.lowHigh=vec4(1.,.5,2.,0.); s.highRender=vec4(0.,0.,.5,.25); s.renderW=vec3(.2,.3,.5); s.purityLow=vec4(0.,.5,.15,.1); s.purityHigh=vec4(0.,.1,0.,1.); s.softClip=vec3(.05,.08,.05); s.midLow=vec3(.8,.35,.5); s.midHigh=vec3(-.9,.5,.3); s.brilliance=vec4(-3.,0.,0.,1.); s.brillianceShape=vec2(.8,.15); s.postBrilliance=vec4(-1.,-1.,-1.,0.); s.hueContrast=vec2(.5,.25); s.hueRgb=vec3(.6,.35,.5); s.hueRgbRange=vec3(1.,2.,1.5); s.hueCmy=vec3(.35,.25,.35); s.hueCmyRange=vec3(1.,1.,.5); s.creativeWhite=1;
    #elif ODRT_LOOK == 5
    s.tone=vec4(1.6,.5,.01,.008); s.lowHigh=vec4(1.,1.,.75,1.); s.highRender=vec4(.25,0.,1.,.2); s.purityLow=vec4(.15,0.,0.,0.); s.purityHigh=vec4(0.,0.,0.,1.); s.softClip=vec3(.05,.08,.05); s.midLow=vec3(.25,.25,.8); s.midHigh=vec3(-.8,.6,.25); s.brilliance=vec4(-2.,-2.,-2.,0.); s.brillianceShape=vec2(.35,.35); s.postBrilliance=vec4(0.,-1.,-1.,-1.); s.hueContrast=vec2(1.,.25); s.hueRgb=vec3(.7,1.,.75); s.hueRgbRange=vec3(1.33,2.,2.); s.hueCmy=vec3(1.); s.hueCmyRange=vec3(.5,1.,.765); s.creativeWhite=2;
    #elif ODRT_LOOK == 6
    s.tone=vec4(1.8,.5,.001,.015); s.lowHigh=vec4(1.,1.,1.,0.); s.purityLow=vec4(0.,.5,0.,.15); s.purityHigh=vec4(.25,.25,0.,1.); s.softClip=vec3(.05,.06,.05); s.midLow=vec3(.4,.35,.66); s.midHigh=vec3(-.6,.45,.45); s.brilliance=vec4(-2.,-4.5,-3.,-4.); s.brillianceShape=vec2(.35,.3); s.postBrilliance=vec4(0.,-2.,-1.,-.5); s.hueContrast.y=.35; s.hueRgb=vec3(.66,.5,.85); s.hueRgbRange=vec3(1.,2.,2.); s.hueCmy=vec3(0.,.25,.66); s.hueCmyRange=vec3(1.,1.,.66); s.creativeWhite=3;
    #endif
    #if ODRT_TONESCALE == 1
    s.tone=vec4(1.4,.5,.003,.005); s.lowHigh=vec4(0.,0.,.5,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 2
    s.tone=vec4(1.66,.5,.003,.005); s.lowHigh=vec4(0.,0.,.5,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 3
    s.tone=vec4(1.4,.5,.003,.005); s.lowHigh=vec4(1.,1.,.5,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 4
    s.tone=vec4(1.05,.5,.1,.01); s.lowHigh=vec4(1.,1.5,.2,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 5
    s.tone=vec4(1.6,.5,.01,.01); s.lowHigh=vec4(1.,.25,.75,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 6
    s.tone=vec4(1.5,.5,.003,.003); s.lowHigh=vec4(1.,.4,.5,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 7
    s.tone=vec4(1.15,.5,.04,.006); s.lowHigh=vec4(1.,.5,2.,0.); s.highRender.xyz=vec3(0.,0.,.5);
    #elif ODRT_TONESCALE == 8
    s.tone=vec4(1.6,.5,.01,.008); s.lowHigh=vec4(1.,1.,.75,1.); s.highRender.xyz=vec3(.25,0.,1.);
    #elif ODRT_TONESCALE == 9
    s.tone=vec4(1.8,.5,.001,.015); s.lowHigh=vec4(1.,1.,1.,0.); s.highRender.xyz=vec3(0.,1.,4.);
    #elif ODRT_TONESCALE == 10
    s.tone=vec4(1.,.35,.02,0.); s.lowHigh=vec4(1.,1.13,1.,1.); s.highRender.xyz=vec3(.55,0.,2.);
    #elif ODRT_TONESCALE == 11
    s.tone=vec4(1.15,.5,.04,0.); s.lowHigh=vec4(0.,1.,.6,0.); s.highRender.xyz=vec3(1.,1.,1.);
    #elif ODRT_TONESCALE == 12
    s.tone=vec4(1.5,.5,.003,.01); s.lowHigh=vec4(1.,1.,1.,1.); s.highRender.xyz=vec3(.25,0.,4.);
    #elif ODRT_TONESCALE == 13
    s.tone=vec4(1.2,.5,.02,0.); s.lowHigh=vec4(0.,0.,.6,0.); s.highRender.xyz=vec3(0.,1.,1.);
    #endif
    return s;
}

#if ODRT_POSTLUT == 1
vec3 applyPostLut(vec3 color) {
    float blue = color.b * (ODRT_POSTLUT_SIZE - 1.0);
    vec2 tile0 = vec2(mod(floor(blue), ODRT_POSTLUT_TILES), floor(floor(blue) / ODRT_POSTLUT_TILES));
    vec2 tile1 = vec2(mod(ceil(blue), ODRT_POSTLUT_TILES), floor(ceil(blue) / ODRT_POSTLUT_TILES));
    vec2 lutPixel = vec2(0.5 / (ODRT_POSTLUT_SIZE * ODRT_POSTLUT_TILES));
    vec2 lutScale = vec2((1.0 / ODRT_POSTLUT_TILES) - 2.0 * lutPixel.x);
    vec2 uv0 = tile0 / ODRT_POSTLUT_TILES + lutPixel + lutScale * color.rg;
    vec2 uv1 = tile1 / ODRT_POSTLUT_TILES + lutPixel + lutScale * color.rg;
    return mix(texture(PostLut, uv0).rgb, texture(PostLut, uv1).rgb, fract(blue));
}
#endif

void main() {
    OpenDrtStyle style = openDrtStyle();
    vec3 rgb = max(texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb, vec3(0.0));
    // OpenDRT input conversion: scene-linear Rec.709 D65 -> P3-D65 rendering space.
    rgb = XYZ_TO_P3 * (REC709_TO_XYZ * rgb);

    float tnOff = style.tone.w;
    float tnShoulder = style.tone.y;
    float rsSat = style.highRender.w;
    vec3 rsW = style.renderW;
    float peak = ODRT_PEAK;
    float con = style.tone.x * (ODRT_CONTRAST / 1.66);
    // Retain the exposed toe control as a relative adjustment to the preset.
    float toe = style.tone.z * (ODRT_TOE / .003);
    float tsX1 = pow(2.0, 6.0*tnShoulder + 4.0);
    float tsY1 = peak / 100.0;
    float tsX0 = 0.18 + tnOff;
    float tsY0 = ODRT_GREY / 100.0 * (1.0 + ODRT_GREY_BOOST * log2(tsY1));
    float tsS0 = toeQuadratic(tsY0, toe, true);
    float tsP = con / 1.10; // sRGB display surround compensation.
    float tsS10 = tsX0 * (pow(tsS0, -1.0/con) - 1.0);
    float tsM1 = tsY1 / pow(tsX1/(tsX1 + tsS10), con);
    float tsM2 = toeQuadratic(tsM1, toe, true);
    float tsS = tsX0 * (pow(tsS0/tsM2, -1.0/con) - 1.0);
    float sLp100 = tsX0 * (pow(ODRT_GREY/100.0, -1.0/con) - 1.0);

    float satL = dot(rgb, rsW);
    rgb = satL*rsSat + rgb*(1.0-rsSat);
    rgb += tnOff;
    float tsn = length(rgb) / SQRT3;
    rgb /= max(tsn, 1e-6);

    vec2 opp = opponent(rgb);
    float ach = length(opp) * 0.5;
    ach = 1.25 * toeQuadratic(ach, 0.25, false);
    float hue = mod(atan(opp.x, opp.y) + PI + 1.10714931, 2.0*PI);
    vec3 hrgb = vec3(gauss(hueOffset(hue,0.1),0.66), gauss(hueOffset(hue,4.3),0.66), gauss(hueOffset(hue,2.3),0.66));
    vec3 hrgbHs = vec3(gauss(hueOffset(hue,-0.4),0.66), hrgb.g, gauss(hueOffset(hue,2.5),0.66));
    vec3 hcmy = vec3(gauss(hueOffset(hue,3.3),0.5), gauss(hueOffset(hue,1.3),0.5), gauss(hueOffset(hue,-1.15),0.5));

    // Standard-look brilliance, hue contrast and hue shifts.
    float brlT = pow(tsn/(tsn+1.0), 1.0-style.brillianceShape.x);
    float brlF = (style.brilliance.x + dot(style.brilliance.yzw, hrgb)) * pow(max(ach,1e-6), 1.0/max(style.brillianceShape.y, .01));
    tsn *= pow(2.0, brlF * (brlF < 0.0 ? brlT : 1.0-brlT));
    if (style.lowHigh.x > .5) {
        float lowM = pow(2.0, -style.lowHigh.y);
        float lowW = style.lowHigh.z * .25;
        lowW *= lowW;
        float lowScale = toeCubic(tsX0, lowM, lowW, true) / tsX0;
        tsn = toeCubic(tsn * lowScale, lowM, lowW, false);
    }
    if (style.lowHigh.w > .5) {
        tsn = contrastHigh(tsn, pow(2.0, style.highRender.x), style.highRender.y, style.highRender.z);
    }
    float tsConst = hyperbolic(tsn, sLp100, tsP);
    float purityBlend = ODRT_HDR_PURITY * min(1.0, max(0.0, (peak - 100.0) / 900.0));
    float tsPt = hyperbolic(tsn, mix(sLp100, tsS, purityBlend), tsP);
    tsn = tsPt;
    float hcTs = 1.0-tsConst;
    float hcC = (hcTs*(1.0-ach) + ach*(1.0-hcTs))*ach*hrgb.r;
    float hcF = style.hueContrast.x*(hcC - 2.0*hcC*pow(hcTs, 1.0/style.hueContrast.y)) + 1.0;
    rgb.gb *= hcF;
    vec3 hsrgb = vec3(hrgbHs.r*ach*pow(tsPt,1.0/style.hueRgbRange.r), hrgbHs.g*ach*pow(tsPt,1.0/style.hueRgbRange.g), hrgbHs.b*ach*pow(tsPt,1.0/style.hueRgbRange.b));
    vec3 hsf = vec3(hsrgb.r*style.hueRgb.r, hsrgb.g*-style.hueRgb.g, hsrgb.b*-style.hueRgb.b);
    rgb += vec3(hsf.b-hsf.g, hsf.r-hsf.b, hsf.g-hsf.r);
    float comp = 1.0-tsPt;
    vec3 hscmy = vec3(hcmy.r*ach*pow(comp,1.0/style.hueCmyRange.r), hcmy.g*ach*pow(comp,1.0/style.hueCmyRange.g), hcmy.b*ach*pow(comp,1.0/style.hueCmyRange.b));
    hsf = vec3(hscmy.r*-style.hueCmy.r, hscmy.g*style.hueCmy.g, hscmy.b*style.hueCmy.b);
    rgb += vec3(hsf.b-hsf.g, hsf.r-hsf.b, hsf.g-hsf.r);

    // Standard-look purity compression.
    float lowP = 1.0 + 4.0*(1.0-tsPt)*(style.purityLow.x + style.purityLow.y*hrgbHs.r + style.purityLow.z*hrgbHs.g + style.purityLow.w*hrgbHs.b);
    float ptf = 1.0 - pow(tsPt, lowP);
    float highP = (1.0-ach*(style.purityHigh.y*hrgbHs.r + style.purityHigh.z*hrgbHs.b))*(1.0-style.purityHigh.x*ach);
    ptf = pow(ptf, highP);
    float midLow = 1.0 + style.midLow.x*exp(-2.0*ach*ach/style.midLow.z)*pow(1.0-tsConst, 1.0/style.midLow.y);
    float midHigh = 1.0 + style.midHigh.x*exp(-2.0*ach*ach/style.midHigh.z)*pow(tsPt, 1.0/(4.0*style.midHigh.y));
    rgb = mix(vec3(1.0), rgb, ptf*midLow*midHigh);

    satL = dot(rgb, rsW);
    rgb = (satL*rsSat-rgb)/(rsSat-1.0);
    vec3 xyz = P3_TO_XYZ * rgb;
    vec3 neutralXyz = xyz;
    if (style.creativeWhite == 1) xyz = D65_TO_D75 * xyz;
    else if (style.creativeWhite == 2) xyz = D65_TO_D60 * xyz;
    else if (style.creativeWhite == 3) xyz = D65_TO_D50 * xyz;
    xyz = mix(neutralXyz, xyz, pow(clamp(tsConst, 0.0, 1.0), 0.5));
    #if ODRT_OUTPUT_P3 == 0
    rgb = XYZ_TO_REC709 * xyz;
    #else
    rgb = XYZ_TO_P3 * xyz;
    #endif
    float postAch = length(opponent(rgb))*0.25;
    postAch = 1.1*(postAch*postAch/(postAch+0.1));
    float postM = style.postBrilliance.x + dot(style.postBrilliance.yzw, ach*hrgb);
    rgb *= pow(2.0, postM*postAch*tsn);
    rgb = vec3(softplus(rgb.r,style.softClip.r), softplus(rgb.g,style.softClip.g), softplus(rgb.b,style.softClip.b));
    tsn = toeQuadratic(tsn*tsM2, toe, false) * (100.0/peak);
    rgb = clamp(rgb*tsn, 0.0, 1.0);
    // OpenDRT's sRGB-display encoding preset uses a 2.2 display power.
    Output = spow(rgb, 1.0/2.2);
    #if ODRT_POSTLUT == 1
    Output = applyPostLut(clamp(Output, 0.0, 1.0));
    #endif
}
