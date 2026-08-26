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

// AgX 2023 inset/outset matrices and its default contrast approximation.
// AgX operates on RGB in log2 space, rather than scaling RGB with a scalar
// luminance curve; this is what keeps saturated highlights well behaved.
const mat3 AGX_INSET = mat3(
    0.8566271533, 0.1373189729, 0.1118982130,
    0.0951212405, 0.7612419906, 0.0767994186,
    0.0482516061, 0.1014390365, 0.8113023684);
const mat3 AGX_OUTSET = mat3(
     1.1271005818, -0.1413297635, -0.1413297635,
    -0.1106066431,  1.1578237022, -0.1106066431,
    -0.0164939387, -0.0164939387,  1.2519364066);

vec3 agxContrast(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
         - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

vec3 agx(vec3 color) {
    color = AGX_INSET * max(color, vec3(0.0));
    color = clamp((log2(max(color, vec3(1e-10))) + 12.47393) / 16.5, 0.0, 1.0);
    color = agxContrast(color);
    color = AGX_OUTSET * color;
    // The AgX outset is display-linear; the following encoding is performed
    // explicitly because the streamed RGBA8 target is not an sRGB texture.
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

vec3 linearToSrgb(vec3 color) {
    vec3 low = 12.92 * color;
    vec3 high = 1.055 * pow(max(color, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(low, high, step(vec3(0.0031308), color));
}

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
    // The CPU histogram supplies scene adaptation only. Its median anchors
    // middle gray, while black and white percentiles stabilize the estimate
    // and reserve highlight headroom. The actual display transform is AgX.
    float exposure = texelFetch(ToneCurve, ivec2(0, 0), 0).r;
    float black = texelFetch(ToneCurve, ivec2(1, 0), 0).r;
    rgb = max(rgb - vec3(black), vec3(0.0)) * exposure;
    rgb = linearToSrgb(clamp(agx(rgb), 0.0, 1.0));
    imageStore(OutputBuffer, gid, vec4(rgb, 1.0));
}
