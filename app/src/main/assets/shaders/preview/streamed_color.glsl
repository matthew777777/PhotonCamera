precision highp float;
precision highp image2D;

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(binding = 0, rgba8) readonly uniform image2D InputBuffer;
layout(binding = 1, rgba8) writeonly uniform image2D OutputBuffer;
uniform sampler2D GainMap;
uniform mat3 sensorToIntermediate; // Color transform from sensor to a wide-gamut colorspace
uniform mat3 intermediateToSRGB; // Color transform from wide-gamut colorspace to sRGB
uniform vec3 neutralPoint;

void main() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(OutputBuffer);
    if (gid.x >= size.x || gid.y >= size.y) return;
    vec3 rgb = imageLoad(InputBuffer, gid).rgb;
    // Lens shading correction, mapped the same way as tofloat.glsl: the RGBA
    // cell packs R, G(even row), G(odd row), B; greens merge, then the gains
    // are normalized by their mean so the correction evens the field without
    // shifting exposure.
    vec4 shading = texture(GainMap, vec2(gid) / vec2(size));
    shading.rgb = vec3(shading.r, (shading.g + shading.b) / 2.0, shading.a);
    shading.rgb /= dot(shading.rgb, vec3(1.0 / 3.0));
    rgb *= shading.rgb;
    // Matrix chain as in initial.glsl applyColorSpace(). The white balance
    // lives INSIDE sensorToIntermediate: in the capture pipeline tofloat.glsl
    // divides by whitePoint and initial.glsl multiplies it back before this
    // same matrix (the two cancel), because sensorToProPhoto already embeds
    // diag(1/neutral). So the matrix is applied to raw sensor RGB;
    // neutralPoint is 1.0 on this path and only used by the identity-matrix
    // fallback, where it carries the plain white-balance gains. Gamma
    // encoding also lives here (moved from the native SuperPixel downsampler).
    rgb = intermediateToSRGB * sensorToIntermediate * (rgb * neutralPoint);
    rgb = pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.2));
    imageStore(OutputBuffer, gid, vec4(rgb, 1.0));
}
