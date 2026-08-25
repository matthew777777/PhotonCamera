#include <jni.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <vector>

namespace {
constexpr int kGramTerms = 10;
constexpr int kTerms = 22;
constexpr int kCoefficientsPerCell = 12;

struct Config {
    int width;
    int height;
    int depth;
    int blur_passes;
    float regularization;
};

struct Workspace {
    std::vector<float> statistics;
    std::vector<float> scratch;
    std::vector<float> coefficients;
    std::array<int64_t, 4> timing_us{}; // splat, blur, solve, total
};

thread_local Workspace workspace;

inline int cell_index(int x, int y, int z, const Config& config) {
    return (z * config.height + y) * config.width + x;
}

inline float scaled_coordinate(int position, int size, int grid_size) {
    return size == 1 ? 0.0f : static_cast<float>(position) * (grid_size - 1) / (size - 1);
}

inline void accumulate_rhs(float* stats, int base, float output,
                           float r, float g, float b, float weight) {
    const float value = weight * output;
    stats[base] += value * r;
    stats[base + 1] += value * g;
    stats[base + 2] += value * b;
    stats[base + 3] += value;
}

inline void accumulate(float* stats, int cell, float r, float g, float b,
                       float out_r, float out_g, float out_b, float weight) {
    if (weight == 0.0f) return;
    const int base = cell * kTerms;
    stats[base] += weight * r * r;
    stats[base + 1] += weight * r * g;
    stats[base + 2] += weight * r * b;
    stats[base + 3] += weight * r;
    stats[base + 4] += weight * g * g;
    stats[base + 5] += weight * g * b;
    stats[base + 6] += weight * g;
    stats[base + 7] += weight * b * b;
    stats[base + 8] += weight * b;
    stats[base + 9] += weight;
    accumulate_rhs(stats, base + kGramTerms, out_r, r, g, b, weight);
    accumulate_rhs(stats, base + kGramTerms + 4, out_g, r, g, b, weight);
    accumulate_rhs(stats, base + kGramTerms + 8, out_b, r, g, b, weight);
}

bool splat(const float* input, const float* target, const float* supplied_guide,
           int image_width, int image_height, const Config& config,
           std::vector<float>& statistics) {
    for (int y = 0; y < image_height; ++y) {
        const float gy = scaled_coordinate(y, image_height, config.height);
        const int y0 = static_cast<int>(std::floor(gy));
        const int y1 = std::min(y0 + 1, config.height - 1);
        const float fy = gy - y0;
        for (int x = 0; x < image_width; ++x) {
            const int pixel = y * image_width + x;
            const int rgb = pixel * 3;
            const float r = input[rgb];
            const float g = input[rgb + 1];
            const float b = input[rgb + 2];
            const float out_r = target[rgb];
            const float out_g = target[rgb + 1];
            const float out_b = target[rgb + 2];
            const float raw_guide = supplied_guide == nullptr
                    ? 0.299f * r + 0.587f * g + 0.114f * b
                    : supplied_guide[pixel];
            if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)
                    || !std::isfinite(out_r) || !std::isfinite(out_g)
                    || !std::isfinite(out_b) || !std::isfinite(raw_guide)) return false;

            const float gx = scaled_coordinate(x, image_width, config.width);
            const float gz = std::max(0.0f, std::min(raw_guide, 1.0f)) * (config.depth - 1);
            const int x0 = static_cast<int>(std::floor(gx));
            const int x1 = std::min(x0 + 1, config.width - 1);
            const int z0 = static_cast<int>(std::floor(gz));
            const int z1 = std::min(z0 + 1, config.depth - 1);
            const float fx = gx - x0;
            const float fz = gz - z0;
            for (int dz = 0; dz < 2; ++dz) {
                const int z = dz == 0 ? z0 : z1;
                const float wz = dz == 0 ? 1.0f - fz : fz;
                for (int dy = 0; dy < 2; ++dy) {
                    const int cy = dy == 0 ? y0 : y1;
                    const float wy = dy == 0 ? 1.0f - fy : fy;
                    for (int dx = 0; dx < 2; ++dx) {
                        const int cx = dx == 0 ? x0 : x1;
                        const float wx = dx == 0 ? 1.0f - fx : fx;
                        accumulate(statistics.data(), cell_index(cx, cy, z, config),
                                   r, g, b, out_r, out_g, out_b, wx * wy * wz);
                    }
                }
            }
        }
    }
    return true;
}

bool splat_rgba8(const unsigned char* input, const unsigned char* target,
                 int image_width, int image_height, const Config& config,
                 std::vector<float>& statistics) {
    constexpr float scale = 1.0f / 255.0f;
    for (int y = 0; y < image_height; ++y) {
        const float gy = scaled_coordinate(y, image_height, config.height);
        const int y0 = static_cast<int>(std::floor(gy));
        const int y1 = std::min(y0 + 1, config.height - 1);
        const float fy = gy - y0;
        for (int x = 0; x < image_width; ++x) {
            const int rgba = (y * image_width + x) * 4;
            const float r = input[rgba] * scale;
            const float g = input[rgba + 1] * scale;
            const float b = input[rgba + 2] * scale;
            const float out_r = target[rgba] * scale;
            const float out_g = target[rgba + 1] * scale;
            const float out_b = target[rgba + 2] * scale;
            const float guide = std::max(0.0f, std::min(
                    0.299f * r + 0.587f * g + 0.114f * b, 1.0f));
            const float gx = scaled_coordinate(x, image_width, config.width);
            const float gz = guide * (config.depth - 1);
            const int x0 = static_cast<int>(std::floor(gx));
            const int x1 = std::min(x0 + 1, config.width - 1);
            const int z0 = static_cast<int>(std::floor(gz));
            const int z1 = std::min(z0 + 1, config.depth - 1);
            const float fx = gx - x0;
            const float fz = gz - z0;
            for (int dz = 0; dz < 2; ++dz) {
                const int z = dz == 0 ? z0 : z1;
                const float wz = dz == 0 ? 1.0f - fz : fz;
                for (int dy = 0; dy < 2; ++dy) {
                    const int cy = dy == 0 ? y0 : y1;
                    const float wy = dy == 0 ? 1.0f - fy : fy;
                    for (int dx = 0; dx < 2; ++dx) {
                        const int cx = dx == 0 ? x0 : x1;
                        const float wx = dx == 0 ? 1.0f - fx : fx;
                        accumulate(statistics.data(), cell_index(cx, cy, z, config),
                                   r, g, b, out_r, out_g, out_b, wx * wy * wz);
                    }
                }
            }
        }
    }
    return true;
}

void blur_axis(const std::vector<float>& source, std::vector<float>& destination,
               const Config& config, int axis) {
    for (int z = 0; z < config.depth; ++z) {
        for (int y = 0; y < config.height; ++y) {
            for (int x = 0; x < config.width; ++x) {
                int px = x, py = y, pz = z, nx = x, ny = y, nz = z;
                if (axis == 0) { px = std::max(0, x - 1); nx = std::min(config.width - 1, x + 1); }
                else if (axis == 1) { py = std::max(0, y - 1); ny = std::min(config.height - 1, y + 1); }
                else { pz = std::max(0, z - 1); nz = std::min(config.depth - 1, z + 1); }
                const int previous = cell_index(px, py, pz, config) * kTerms;
                const int center = cell_index(x, y, z, config) * kTerms;
                const int next = cell_index(nx, ny, nz, config) * kTerms;
                for (int term = 0; term < kTerms; ++term) {
                    destination[center + term] = (source[previous + term]
                            + 2.0f * source[center + term] + source[next + term]) * 0.25f;
                }
            }
        }
    }
}

bool gauss_jordan(double matrix[4][7]) {
    for (int pivot = 0; pivot < 4; ++pivot) {
        int best = pivot;
        for (int row = pivot + 1; row < 4; ++row)
            if (std::abs(matrix[row][pivot]) > std::abs(matrix[best][pivot])) best = row;
        if (std::abs(matrix[best][pivot]) < 1.0e-12) return false;
        for (int column = 0; column < 7; ++column)
            std::swap(matrix[pivot][column], matrix[best][column]);
        const double divisor = matrix[pivot][pivot];
        for (int column = pivot; column < 7; ++column) matrix[pivot][column] /= divisor;
        for (int row = 0; row < 4; ++row) {
            if (row == pivot) continue;
            const double factor = matrix[row][pivot];
            for (int column = pivot; column < 7; ++column)
                matrix[row][column] -= factor * matrix[pivot][column];
        }
    }
    return true;
}

inline void set_identity(float* coefficients) {
    coefficients[0] = 1.0f;
    coefficients[5] = 1.0f;
    coefficients[10] = 1.0f;
}

void solve(const std::vector<float>& statistics, const Config& config,
           std::vector<float>& coefficients) {
    const int cells = config.width * config.height * config.depth;
    for (int cell = 0; cell < cells; ++cell) {
        const int base = cell * kTerms;
        double system[4][7] = {};
        int term = 0;
        for (int row = 0; row < 4; ++row) {
            for (int column = row; column < 4; ++column) {
                system[row][column] = system[column][row] = statistics[base + term++];
            }
        }
        float* output = coefficients.data() + cell * kCoefficientsPerCell;
        const double mass = system[3][3];
        if (mass < 1.0e-8) { set_identity(output); continue; }
        for (int channel = 0; channel < 3; ++channel)
            for (int input = 0; input < 4; ++input)
                system[input][4 + channel] = statistics[base + term++];
        const double ridge = config.regularization * mass;
        for (int i = 0; i < 4; ++i) system[i][i] += ridge;
        system[0][4] += ridge;
        system[1][5] += ridge;
        system[2][6] += ridge;
        if (!gauss_jordan(system)) { set_identity(output); continue; }
        for (int channel = 0; channel < 3; ++channel)
            for (int input = 0; input < 4; ++input)
                output[channel * 4 + input] = static_cast<float>(system[input][4 + channel]);
    }
}

void finish_estimate(int cells, const Config& config,
                     std::chrono::steady_clock::time_point started) {
    const auto blur_started = std::chrono::steady_clock::now();
    for (int pass = 0; pass < config.blur_passes; ++pass) {
        blur_axis(workspace.statistics, workspace.scratch, config, 0);
        blur_axis(workspace.scratch, workspace.statistics, config, 1);
        blur_axis(workspace.statistics, workspace.scratch, config, 2);
        workspace.statistics.swap(workspace.scratch);
    }
    const auto solve_started = std::chrono::steady_clock::now();
    workspace.coefficients.assign(static_cast<size_t>(cells) * kCoefficientsPerCell, 0.0f);
    solve(workspace.statistics, config, workspace.coefficients);
    const auto finished = std::chrono::steady_clock::now();
    workspace.timing_us[1] = std::chrono::duration_cast<std::chrono::microseconds>(
            solve_started - blur_started).count();
    workspace.timing_us[2] = std::chrono::duration_cast<std::chrono::microseconds>(
            finished - solve_started).count();
    workspace.timing_us[3] = std::chrono::duration_cast<std::chrono::microseconds>(
            finished - started).count();
}

bool estimate(const float* input, const float* target, const float* guide,
              int image_width, int image_height, const Config& config) {
    const auto started = std::chrono::steady_clock::now();
    const int cells = config.width * config.height * config.depth;
    workspace.statistics.assign(static_cast<size_t>(cells) * kTerms, 0.0f);
    workspace.scratch.resize(workspace.statistics.size());
    if (!splat(input, target, guide, image_width, image_height, config,
               workspace.statistics)) return false;
    const auto splat_finished = std::chrono::steady_clock::now();
    workspace.timing_us[0] = std::chrono::duration_cast<std::chrono::microseconds>(
            splat_finished - started).count();
    finish_estimate(cells, config, started);
    return true;
}

bool estimate_rgba8(const unsigned char* input, const unsigned char* target,
                    int image_width, int image_height, const Config& config) {
    const auto started = std::chrono::steady_clock::now();
    const int cells = config.width * config.height * config.depth;
    workspace.statistics.assign(static_cast<size_t>(cells) * kTerms, 0.0f);
    workspace.scratch.resize(workspace.statistics.size());
    if (!splat_rgba8(input, target, image_width, image_height, config,
                     workspace.statistics)) return false;
    const auto splat_finished = std::chrono::steady_clock::now();
    workspace.timing_us[0] = std::chrono::duration_cast<std::chrono::microseconds>(
            splat_finished - started).count();
    finish_estimate(cells, config, started);
    return true;
}

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

jfloatArray make_result(JNIEnv* env, const float* input, const float* target,
                        const float* guide, jint image_width, jint image_height,
                        const Config& config) {
    if (!estimate(input, target, guide, image_width, image_height, config)) {
        throw_illegal_argument(env, "Non-finite estimator input");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(workspace.coefficients.size()));
    if (result != nullptr)
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(workspace.coefficients.size()),
                                 workspace.coefficients.data());
    return result;
}
}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_BilateralGridEstimator_nativeEstimateArrays(
        JNIEnv* env, jclass, jfloatArray input_array, jfloatArray target_array,
        jfloatArray guide_array, jint image_width, jint image_height,
        jint grid_width, jint grid_height, jint grid_depth, jint blur_passes,
        jfloat regularization) {
    jfloat* input = env->GetFloatArrayElements(input_array, nullptr);
    jfloat* target = env->GetFloatArrayElements(target_array, nullptr);
    jfloat* guide = guide_array == nullptr ? nullptr : env->GetFloatArrayElements(guide_array, nullptr);
    if (input == nullptr || target == nullptr || (guide_array != nullptr && guide == nullptr)) {
        if (input != nullptr) env->ReleaseFloatArrayElements(input_array, input, JNI_ABORT);
        if (target != nullptr) env->ReleaseFloatArrayElements(target_array, target, JNI_ABORT);
        if (guide != nullptr) env->ReleaseFloatArrayElements(guide_array, guide, JNI_ABORT);
        return nullptr;
    }
    Config config{grid_width, grid_height, grid_depth, blur_passes, regularization};
    jfloatArray result = make_result(env, input, target, guide, image_width, image_height, config);
    env->ReleaseFloatArrayElements(input_array, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(target_array, target, JNI_ABORT);
    if (guide != nullptr) env->ReleaseFloatArrayElements(guide_array, guide, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_BilateralGridEstimator_nativeGetLastTimingUs(
        JNIEnv* env, jclass) {
    jlong values[4];
    for (int i = 0; i < 4; ++i) values[i] = workspace.timing_us[i];
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_BilateralGridEstimator_nativeEstimateDirect(
        JNIEnv* env, jclass, jobject input_buffer, jobject target_buffer,
        jobject guide_buffer, jint image_width, jint image_height,
        jint grid_width, jint grid_height, jint grid_depth, jint blur_passes,
        jfloat regularization) {
    auto* input = static_cast<float*>(env->GetDirectBufferAddress(input_buffer));
    auto* target = static_cast<float*>(env->GetDirectBufferAddress(target_buffer));
    auto* guide = guide_buffer == nullptr ? nullptr
                                         : static_cast<float*>(env->GetDirectBufferAddress(guide_buffer));
    if (input == nullptr || target == nullptr || (guide_buffer != nullptr && guide == nullptr)) {
        throw_illegal_argument(env, "Estimator buffers must be direct");
        return nullptr;
    }
    Config config{grid_width, grid_height, grid_depth, blur_passes, regularization};
    return make_result(env, input, target, guide, image_width, image_height, config);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_particlesdevs_photoncamera_ui_camera_views_viewfinder_BilateralGridEstimator_nativeEstimateRgba8(
        JNIEnv* env, jclass, jobject input_buffer, jobject target_buffer,
        jint image_width, jint image_height, jint grid_width, jint grid_height,
        jint grid_depth, jint blur_passes, jfloat regularization) {
    auto* input = static_cast<unsigned char*>(env->GetDirectBufferAddress(input_buffer));
    auto* target = static_cast<unsigned char*>(env->GetDirectBufferAddress(target_buffer));
    if (input == nullptr || target == nullptr) {
        throw_illegal_argument(env, "RGBA estimator buffers must be direct");
        return nullptr;
    }
    Config config{grid_width, grid_height, grid_depth, blur_passes, regularization};
    if (!estimate_rgba8(input, target, image_width, image_height, config)) {
        throw_illegal_argument(env, "Invalid RGBA estimator input");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(workspace.coefficients.size()));
    if (result != nullptr)
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(workspace.coefficients.size()),
                                 workspace.coefficients.data());
    return result;
}
