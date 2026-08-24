#define LAYOUT //
LAYOUT
precision highp float;

// An 8x8 output block is expanded to a 16x16 source tile. Adjacent workgroups
// overlap by the four-pixel halo, and every lane cooperatively loads four samples.
layout(rgba16f, binding = 0) uniform highp readonly image2D SourceBuffer;
layout(rgba16f, binding = 1) uniform highp readonly image2D OriginalBuffer;
layout(rgba16f, binding = 2) uniform highp readonly image2D BlurredEstimate;
layout(rgba16f, binding = 3) uniform highp writeonly image2D OutputBuffer;

uniform int mode; // 0: blur SourceBuffer luminance; 1: blur O / (G * estimate)
uniform float radius;
uniform float cornerBoost;
uniform float epsilon;

const int LOCAL_SIZE = 8;
const int TILE_SIZE = 16;
const int HALO = 4;
const vec3 LUMA = vec3(0.212671, 0.715160, 0.072169);
// Literal shared-array dimensions keep this valid on stricter GLES 3.1 drivers.
shared float tile[16][16];

ivec2 clampCoord(ivec2 point, ivec2 size) { return clamp(point, ivec2(0), size - ivec2(1)); }

float imageValue(ivec2 point, ivec2 size) {
    point = clampCoord(point, size);
    if (mode == 0) return dot(imageLoad(SourceBuffer, point).rgb, LUMA);
    return dot(imageLoad(OriginalBuffer, point).rgb, LUMA) /
        max(imageLoad(BlurredEstimate, point).r, epsilon);
}

float tileValue(ivec2 point, ivec2 workgroupStart, ivec2 size) {
    ivec2 local = point - workgroupStart + ivec2(HALO);
    // Radius 2 can reach six pixels. Its outer fringe falls back to image loads;
    // all samples inside the overlapping 16x16 window stay in shared memory.
    if (all(greaterThanEqual(local, ivec2(0))) && all(lessThan(local, ivec2(TILE_SIZE))))
        return tile[local.y][local.x];
    return imageValue(point, size);
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    ivec2 size = imageSize(SourceBuffer);
    ivec2 workgroupStart = ivec2(gl_WorkGroupID.xy) * ivec2(LOCAL_SIZE);

    int lane = lid.y * LOCAL_SIZE + lid.x;
    for (int tap = 0; tap < 4; ++tap) {
        int index = lane + tap * LOCAL_SIZE * LOCAL_SIZE;
        ivec2 sharedPos = ivec2(index % TILE_SIZE, index / TILE_SIZE);
        tile[sharedPos.y][sharedPos.x] = imageValue(workgroupStart + sharedPos - ivec2(HALO), size);
    }
    memoryBarrierShared();
    barrier();

    if (any(greaterThanEqual(xy, size))) return;
    vec2 center = (vec2(xy) + 0.5 - 0.5 * vec2(size)) / (0.5 * length(vec2(size)));
    float sigma = min(2.0, radius + cornerBoost * length(center));
    int halfWidth = sigma < 0.6 ? 1 : sigma <= 0.84 ? 2 : sigma <= 1.15 ? 3 : sigma <= 1.5 ? 4 : 6;
    float support2 = halfWidth == 1 ? 2.0 : halfWidth == 2 ? 6.3504 : halfWidth == 3 ? 11.9025 : halfWidth == 4 ? 20.25 : 36.0;
    float twoSigma2 = 2.0 * max(sigma * sigma, 0.0004);
    float sum = 0.0;
    float weightSum = 0.0;
    for (int y = -6; y <= 6; ++y) for (int x = -6; x <= 6; ++x) {
        int distance2i = x * x + y * y;
        if (abs(x) > halfWidth || abs(y) > halfWidth || float(distance2i) > support2) continue;
        float weight = exp(-float(distance2i) / twoSigma2);
        sum += tileValue(xy + ivec2(x, y), workgroupStart, size) * weight;
        weightSum += weight;
    }
    imageStore(OutputBuffer, xy, vec4(vec3(sum / weightSum), 1.0));
}
