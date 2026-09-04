precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
#define LAYOUT //
#define TILE 2
#define CONCAT 1

vec4 getBayer(ivec2 coords){
    return vec4(imageLoad(inTexture,coords).r, imageLoad(inTexture,coords+ivec2(1,0)).r,imageLoad(inTexture,coords+ivec2(0,1)).r,imageLoad(inTexture,coords+ivec2(1,1)).r);
}

LAYOUT
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    #if CONCAT == 1
        vec4 bayer = getBayer(xy*2);
        imageStore(outTexture, xy, bayer);
    #else
        vec4 bayer = imageLoad(inTexture,xy);
        imageStore(outTexture, xy*2, vec4(bayer.r));
        imageStore(outTexture, xy*2+ivec2(1,0), vec4(bayer.g));
        imageStore(outTexture, xy*2+ivec2(0,1), vec4(bayer.b));
        imageStore(outTexture, xy*2+ivec2(1,1), vec4(bayer.a));
    #endif
}
