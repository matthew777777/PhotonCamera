precision highp float;
precision highp sampler2D;
uniform highp sampler2D inTexture;
uniform highp sampler2D alignmentTexture;
uniform int yOffset;
// Sensor red-site offset ((cfa%2, cfa/2)); passed as a uniform because GLProg
// clears its define list after every program load, so a CFAPATTERN define set
// once at pipeline start would never reach this late-bound shader.
uniform ivec2 cfaShift;
#define WHITE_LEVEL 0.0
#define BLACK_LEVEL 0.0
#define TILE 2
#define CONCAT 1
out uint Output;


// Per-site white noise for dithering the fp16 -> uint16 quantization: the
// accumulated base carries only ~11 mantissa bits, so mapping it onto 65535
// output levels would band; a +/-0.5 LSB hash dither converts the residual
// quantization error into zero-mean noise instead.
float hash12(vec2 p){
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

uvec4 getBayerVec(ivec2 coords, ivec2 rawSite){
    float dither = hash12(vec2(rawSite));
    return min(uvec4(floor(clamp(texelFetch(inTexture, coords, 0),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL) + dither)), uvec4(WHITE_LEVEL));
}


void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    // Undo the merge00 packing shift: real raw site X lives at packed
    // rel = X + cfaShift (merge00 shifted quad origins by -cfaShift and
    // filled the out-of-range sites with edge duplicates, which land at
    // rel < cfaShift and rel > rawSize-1 and are simply never read here;
    // cfaShift is zero for RGGB/BGGR, so this is the identity for them).
    ivec2 rel = xy + cfaShift;
    uvec4 bayer = getBayerVec(rel / TILE, rel);
    Output = bayer[(rel.x & 1) + (rel.y & 1) * TILE];
}
