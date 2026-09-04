precision highp float;
precision highp sampler2D;
//uniform sampler2D InputBuffer;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
uniform int yOffset;

#define TILE 3
#define SIGMA 10.0
#define BSIGMA 0.1
#define KERNELSIZE 3.5
#define MSIZE 15
#define KSIZE (MSIZE-1)/2
#define PI 3.1415926535897932384626433832795
#define LAYOUT //

float normpdf(in float x, in float sigma)
{
    return 0.39894*exp(-0.5*x*x/(sigma*sigma))/sigma;
}
float normpdf3(in vec3 v, in float sigma)
{
    return 0.39894*exp(-0.5*dot(v,v)/(sigma*sigma))/sigma;
}
float normpdf2(in vec2 v, in float sigma)
{
    return 0.39894*exp(-0.5*dot(v,v)/(sigma*sigma))/sigma;
}

float lum(in vec4 color) {
    return length(color.xyz);
}

float atan2(in float y, in float x) {
    bool s = (abs(x) > abs(y));
    return mix(PI/2.0 - atan(x,y+0.00001), atan(y,x+0.00001), s);
}

vec4 getBayer(ivec2 coords){
    return imageLoad(inTexture,coords);
    //return vec4(imageLoad(inTexture,coords).r, imageLoad(inTexture,coords+ivec2(1,0)).r,imageLoad(inTexture,coords+ivec2(0,1)).r,imageLoad(inTexture,coords+ivec2(1,1)).r);
}
vec4 getColor(ivec2 coords){
    return vec4(imageLoad(inTexture,coords).r, 0.0, 0.0, imageLoad(inTexture,coords+ivec2(1,1)).r);
}

vec2 getCoeff(ivec2 coords){
        // get colour coefficient
        vec4 c = getBayer(coords);
        float l = c[1]+c[2];
        float a = c[0]/l;
        float b = c[3]/l;
        return vec2(a,b);
}

LAYOUT
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    xy+=ivec2(0,yOffset);
    vec2 cin = vec2(getCoeff(xy));
    vec4 c = getBayer(xy);
    float l = c[1]+c[2];
    //float noisefactor = dot(cin,vec4(0.15,0.35,0.35,0.15));
    vec2 final_colour = vec2(0.0);
    float sigX = 2.5;
    //float sigY = (noisefactor*noisefactor*NOISES + NOISEO + 0.0000001);
    float sigY = 1.0;
    float Z = 0.01f;
    final_colour += cin*Z;
    //sigY /= 25.0;
    // Use hybrid SNN filtering to denoise the image
    for (int i=0; i <= KSIZE; ++i)
    {
        for (int j=0; j <= KSIZE; ++j)
        {
            ivec2 pos = ivec2(i,j);
            ivec2 pos2 = ivec2(-i,-j);
            ivec2 pos3 = ivec2(i,-j);
            ivec2 pos4 = ivec2(-i,j);
            vec2 cc1 = vec2(getCoeff(xy+pos));
            vec2 cc2 = vec2(getCoeff(xy+pos2));
            vec2 cc3 = vec2(getCoeff(xy+pos3));
            vec2 cc4 = vec2(getCoeff(xy+pos4));
            // Compute the weights
            //vec4 priority = vec4(0.5,1.16,1.16,0.5)*1.25;
            vec2 priority = vec2(1.0);
            float d1 = length(abs(cc1-cin)*priority);
            float d2 = length(abs(cc2-cin)*priority);
            float d3 = length(abs(cc3-cin)*priority);
            float d4 = length(abs(cc4-cin)*priority);
            float w1 = (1.0-d1*d1/(d1*d1 + sigY));
            float w2 = (1.0-d2*d2/(d2*d2 + sigY));
            float w3 = (1.0-d3*d3/(d3*d3 + sigY));
            float w4 = (1.0-d4*d4/(d4*d4 + sigY));
            float wm = min(min(min(w1,w2),w3),w4);
            w1 -= wm;
            w2 -= wm;
            w3 -= wm;
            w4 -= wm;
            float f1 = normpdf(float(i),KERNELSIZE)*normpdf(float(j),KERNELSIZE);
            float factor = 0.0;
            factor += f1*(w1);
            factor += f1*(w2);
            factor += f1*(w3);
            factor += f1*(w4);
            final_colour += f1*w1*cc1;
            final_colour += f1*w2*cc2;
            final_colour += f1*w3*cc3;
            final_colour += f1*w4*cc4;
            Z += factor;
        }
    }

    //if (Z <= 0.002f) {
    //    Output = vec4(cin,1.0);
    //} else {
    //Output = vec4(clamp(final_colour/Z,0.0,1.0),1.0);
    //vec4 outBayer = vec4(0.0);
    //vec4 bIn = getBayer(xy);
    final_colour /= Z;
    vec4 fc = vec4(final_colour[0]*l,c[1],c[2],final_colour[1]*l);
    vec4 outBayer = vec4(clamp(fc,0.0,1.0));
    imageStore(outTexture,xy,vec4(outBayer));
    //imageStore(outTexture,xy+ivec2(1,0),vec4(outBayer.g));
    //imageStore(outTexture,xy+ivec2(0,1),vec4(outBayer.b));
    //imageStore(outTexture,xy+ivec2(1,1),vec4(outBayer.a));
    //}
}
