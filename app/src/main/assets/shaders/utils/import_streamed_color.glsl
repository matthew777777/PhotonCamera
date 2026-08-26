uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPoint;
uniform vec4 gainMapTransform;

vec3 streamedLinearColor(ivec2 position, ivec2 size) {
    vec3 rgb = texelFetch(InputBuffer, position, 0).rgb;
    vec2 outputUv = (vec2(position) + 0.5) / vec2(size);
    vec2 gainUv = outputUv * gainMapTransform.xy + gainMapTransform.zw;
    vec4 packedGain = texture(GainMap, gainUv);
    vec3 shading = vec3(packedGain.r,
            (packedGain.g + packedGain.b) * 0.5, packedGain.a);
    rgb *= clamp(shading, vec3(0.25), vec3(8.0));
    return intermediateToSRGB * sensorToIntermediate * (rgb * neutralPoint);
}

float streamedLuminance(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float streamedLogCoordinate(float luminance) {
    return clamp((log2(max(luminance, 0.000244140625)) + 12.0) / 14.0,
            0.0, 1.0);
}
