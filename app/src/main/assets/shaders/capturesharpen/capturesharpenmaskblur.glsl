#version 310 es

precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;

// RawTherapee gaussHorizontalSse/gaussVerticalSse at sigma=2, expressed as
// one independent sequential invocation per row/column. The recurrence and
// Young-van Vliet boundary conditions are unchanged; rows/columns run in
// parallel on the GPU.
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

uniform sampler2D InputBuffer;
uniform ivec2 size;
#ifndef HORIZONTAL
#define HORIZONTAL 1
#endif

layout(binding = 0, r32f) uniform highp image2D OutputBuffer;

const float B  = 0.22728525109080389;
const float B1 = 1.2327675485381264;
const float B2 = -0.5533139546361017;
const float B3 = 0.09326115500717135;
const vec3 M0 = vec3(1.207969972705353, -0.4911294089563934, 0.09307619696681323);
const vec3 M1 = vec3(0.998016773003252, -0.5753104457124495, 0.11265667486848248);
const vec3 M2 = vec3(0.6550122449427013, -0.43955993259510745, 0.09307619696681323);

ivec2 coordinate(int line, int position) {
#if HORIZONTAL
    return ivec2(position, line);
#else
    return ivec2(line, position);
#endif
}

float sourceAt(int line, int position) {
    return texelFetch(InputBuffer, coordinate(line, position), 0).r;
}

float forwardAt(int line, int position) {
    return imageLoad(OutputBuffer, coordinate(line, position)).r;
}

void storeValue(int line, int position, float value) {
    imageStore(OutputBuffer, coordinate(line, position), vec4(value));
}

void main() {
    int line = int(gl_GlobalInvocationID.x);
#if HORIZONTAL
    int lineCount = size.y;
    int length = size.x;
#else
    int lineCount = size.x;
    int length = size.y;
#endif
    if (line >= lineCount || length < 3) return;

    float edge0 = sourceAt(line, 0);
    float fm3 = edge0 * (B + B1 + B2 + B3);
    storeValue(line, 0, fm3);
    float fm2 = B * sourceAt(line, 1) + B1 * fm3 + (B2 + B3) * edge0;
    storeValue(line, 1, fm2);
    float current = B * sourceAt(line, 2) + B1 * fm2 + B2 * fm3 + B3 * edge0;
    storeValue(line, 2, current);

    for (int p = 3; p < length; ++p) {
        float previous = current;
        current = B * sourceAt(line, p) + B1 * previous + B2 * fm2 + B3 * fm3;
        storeValue(line, p, current);
        fm3 = fm2;
        fm2 = previous;
    }

    float edge1 = sourceAt(line, length - 1);
    vec3 delta = vec3(current, fm2, fm3) - edge1;
    float tempWm1 = edge1 + dot(M0, delta);
    float tempW   = edge1 + dot(M1, delta);
    float tempWp1 = edge1 + dot(M2, delta);

    current = tempWm1;
    storeValue(line, length - 1, current);
    fm2 = B * fm2 + B1 * current + B2 * tempW + B3 * tempWp1;
    storeValue(line, length - 2, fm2);
    fm3 = B * fm3 + B1 * fm2 + B2 * current + B3 * tempW;
    storeValue(line, length - 3, fm3);

    float next1 = fm3;
    float next2 = fm2;
    float next3 = current;
    memoryBarrierImage();
    for (int p = length - 4; p >= 0; --p) {
        float value = B * forwardAt(line, p) + B1 * next1 + B2 * next2 + B3 * next3;
        storeValue(line, p, value);
        next3 = next2;
        next2 = next1;
        next1 = value;
    }
}
