precision highp float;
precision highp image2D;

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(binding = 0, rgba8) readonly uniform image2D InputBuffer;
layout(binding = 1, rgba8) writeonly uniform image2D OutputBuffer;
uniform sampler2D GainMap;
uniform sampler2D ToneCurve;
uniform vec4 gainMapTransform;
uniform mat3 sensorToIntermediate; // Color transform from sensor to a wide-gamut colorspace
uniform mat3 intermediateToSRGB; // Color transform from wide-gamut colorspace to sRGB
uniform vec3 neutralPoint;
uniform vec3 whitePointScale; // Undoes the native min(whitePoint)/whitePoint encoding

void main() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(OutputBuffer);
    if (gid.x >= size.x || gid.y >= size.y) return;
    vec3 rgb = imageLoad(InputBuffer, gid).rgb;
    // The native SuperPixel stores each channel divided by its white point and
    // scaled by the smallest one; multiply the ratios back to restore true
    // brightness before anything else touches the values.
    rgb *= whitePointScale;
    // Lens shading correction, mapped the same way as tofloat.glsl: the RGBA
    // cell packs R, G(even row), G(odd row), B; greens merge, then the gains
    // are normalized by their mean so the correction evens the field without
    // shifting exposure. gainMapTransform remaps the output UV into the 4:3
    // crop the preview shows inside the full raw frame the map covers.
    vec2 outputUv = (vec2(gid) + 0.5) / vec2(size);
    vec2 gainUv = outputUv * gainMapTransform.xy + gainMapTransform.zw;
    vec4 shading = texture(GainMap, gainUv);
    shading.rgb = vec3(shading.r, (shading.g + shading.b) / 2.0, shading.a);
    shading.rgb /= dot(shading.rgb, vec3(1.0 / 3.0));
    rgb *= shading.rgb;
    // Matrix chain as in initial.glsl applyColorSpace(). The white balance
    // lives INSIDE sensorToIntermediate: in the capture pipeline tofloat.glsl
    // divides by whitePoint and initial.glsl multiplies it back before this
    // same matrix (the two cancel), because sensorToProPhoto already embeds
    // diag(1/neutral). So the matrix is applied to raw sensor RGB;
    // neutralPoint is 1.0 on this path and only used by the identity-matrix
    // fallback, where it carries the plain white-balance gains.
    rgb = intermediateToSRGB * sensorToIntermediate * (rgb * neutralPoint);
    // Histogram GTM built by the native SuperPixel pass on the same frame:
    // the curve maps the log2 luminance coordinate to display luminance, so
    // scale RGB to hit the mapped luminance while preserving chromaticity.
    float luma = max(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 1e-6);
    float coordinate = clamp((log2(luma) + 12.0) / 14.0, 0.0, 1.0);
    float mapped = texture(ToneCurve, vec2(coordinate, 0.5)).r;
    rgb *= mapped / luma;
    // Gamma encoding also lives here (moved from the native SuperPixel downsampler).
    rgb = pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.2));
    imageStore(OutputBuffer, gid, vec4(rgb, 1.0));
}
