#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <sched.h>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define BGU_NEON 1
#else
#define BGU_NEON 0
#endif

namespace {
// Statistics rows are padded to 24 floats (a 96-byte, 16-byte-aligned block
// per cell) so the per-cell accumulation vectorizes cleanly; the two pad
// terms are always written as zero.
constexpr int kStatsStride = 24;
constexpr int kCoefficientsPerCell = 12;
constexpr int kMaxSplatThreads = 8;

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

// Adds weight * base into one cell's statistics row. The base products are
// computed once per pixel by splat_pixel; only the scalar weight varies
// between the eight trilinear cells, so this is a pure FMA loop.
inline void accumulate_cell(float* stats, int cell, const float* base, float weight) {
    float* row = stats + static_cast<size_t>(cell) * kStatsStride;
#if BGU_NEON
    const float32x4_t w = vdupq_n_f32(weight);
    for (int j = 0; j < kStatsStride; j += 4)
        vst1q_f32(row + j, vmlaq_f32(vld1q_f32(row + j), vld1q_f32(base + j), w));
#else
    for (int j = 0; j < kStatsStride; ++j) row[j] += weight * base[j];
#endif
}

inline void splat_pixel(float* statistics, const Config& config,
                        float r, float g, float b,
                        float out_r, float out_g, float out_b,
                        float raw_guide, float gx, float gy) {
    const float gz = std::max(0.0f, std::min(raw_guide, 1.0f)) * (config.depth - 1);
    const int x0 = static_cast<int>(std::floor(gx));
    const int x1 = std::min(x0 + 1, config.width - 1);
    const int y0 = static_cast<int>(std::floor(gy));
    const int y1 = std::min(y0 + 1, config.height - 1);
    const int z0 = static_cast<int>(std::floor(gz));
    const int z1 = std::min(z0 + 1, config.depth - 1);
    const float fx = gx - x0;
    const float fy = gy - y0;
    const float fz = gz - z0;
    alignas(16) float base[kStatsStride];
    base[0] = r * r;
    base[1] = r * g;
    base[2] = r * b;
    base[3] = r;
    base[4] = g * g;
    base[5] = g * b;
    base[6] = g;
    base[7] = b * b;
    base[8] = b;
    base[9] = 1.0f;
    base[10] = out_r * r;
    base[11] = out_r * g;
    base[12] = out_r * b;
    base[13] = out_r;
    base[14] = out_g * r;
    base[15] = out_g * g;
    base[16] = out_g * b;
    base[17] = out_g;
    base[18] = out_b * r;
    base[19] = out_b * g;
    base[20] = out_b * b;
    base[21] = out_b;
    base[22] = 0.0f;
    base[23] = 0.0f;
    for (int dz = 0; dz < 2; ++dz) {
        const int z = dz == 0 ? z0 : z1;
        const float wz = dz == 0 ? 1.0f - fz : fz;
        for (int dy = 0; dy < 2; ++dy) {
            const int cy = dy == 0 ? y0 : y1;
            const float wy = dy == 0 ? 1.0f - fy : fy;
            for (int dx = 0; dx < 2; ++dx) {
                const int cx = dx == 0 ? x0 : x1;
                const float wx = dx == 0 ? 1.0f - fx : fx;
                accumulate_cell(statistics, cell_index(cx, cy, z, config),
                                base, wx * wy * wz);
            }
        }
    }
}

// Splat one row band of the float path into a private statistics buffer.
// Returns false when a non-finite sample is found; the caller abandons the estimate.
bool splat_float_band(const float* input, const float* target, const float* supplied_guide,
                      int image_width, int image_height, int y_begin, int y_end,
                      const Config& config, float* statistics) {
    for (int y = y_begin; y < y_end; ++y) {
        const float gy = scaled_coordinate(y, image_height, config.height);
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
            splat_pixel(statistics, config, r, g, b, out_r, out_g, out_b,
                        raw_guide, gx, gy);
        }
    }
    return true;
}

void splat_rgba8_band(const unsigned char* input, const unsigned char* target,
                      int image_width, int image_height, int y_begin, int y_end,
                      const Config& config, float* statistics) {
    constexpr float scale = 1.0f / 255.0f;
    for (int y = y_begin; y < y_end; ++y) {
        const float gy = scaled_coordinate(y, image_height, config.height);
        for (int x = 0; x < image_width; ++x) {
            const int rgba = (y * image_width + x) * 4;
            const float r = input[rgba] * scale;
            const float g = input[rgba + 1] * scale;
            const float b = input[rgba + 2] * scale;
            const float guide = std::max(0.0f, std::min(
                    0.299f * r + 0.587f * g + 0.114f * b, 1.0f));
            const float gx = scaled_coordinate(x, image_width, config.width);
            splat_pixel(statistics, config, r, g, b,
                        target[rgba] * scale, target[rgba + 1] * scale,
                        target[rgba + 2] * scale, guide, gx, gy);
        }
    }
}

// The scattered-add splat is latency-bound, so a worker scheduled on a
// little core drags the whole frame to that core's speed. Pinning the
// workers to the fastest cluster removes that imbalance. The mask is
// resolved once from sysfs max frequencies; 0 means "detection failed",
// in which case workers keep the default scheduling.
const cpu_set_t& big_core_mask() {
    static const cpu_set_t mask = [] {
        cpu_set_t set;
        CPU_ZERO(&set);
        long best = -1;
        int cpus[64];
        long freqs[64];
        int count = 0;
        for (int cpu = 0; cpu < 64; ++cpu) {
            char path[96];
            std::snprintf(path, sizeof(path),
                          "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
            if (FILE* file = std::fopen(path, "r")) {
                long freq = -1;
                if (std::fscanf(file, "%ld", &freq) == 1 && freq > 0 && count < 64) {
                    cpus[count] = cpu;
                    freqs[count] = freq;
                    ++count;
                    if (freq > best) best = freq;
                }
                std::fclose(file);
            }
        }
        if (best <= 0) return set;  // leave empty: caller falls back
        for (int i = 0; i < count; ++i)
            if (freqs[i] >= best - best / 16) CPU_SET(cpus[i], &set);
        return set;
    }();
    return mask;
}

int splat_thread_count(int image_height) {
    const cpu_set_t& mask = big_core_mask();
    int big = CPU_COUNT(&mask);
    if (big <= 0) {
        const unsigned hardware = std::thread::hardware_concurrency();
        big = std::max(1, static_cast<int>(hardware == 0 ? 2 : hardware));
    }
    return std::max(1, std::min({kMaxSplatThreads, big, image_height}));
}

// Runs the band functor in parallel over row bands, each accumulating into a
// private statistics buffer, then reduces the buffers into `statistics`.
// The float path may abort early on non-finite input; `valid` reports that.
template <typename Band>
bool parallel_splat(int image_height, const Config& config, float* statistics, Band band) {
    const int threads = splat_thread_count(image_height);
    const int cells = config.width * config.height * config.depth;
    const size_t terms = static_cast<size_t>(cells) * kStatsStride;
    if (threads == 1) {
        std::vector<float> local(terms, 0.0f);
        if (!band(0, image_height, local.data())) return false;
        std::copy(local.begin(), local.end(), statistics);
        return true;
    }
    const cpu_set_t& mask = big_core_mask();
    std::vector<std::vector<float>> locals(threads, std::vector<float>(terms, 0.0f));
    std::atomic<bool> valid{true};
    std::vector<std::thread> workers;
    workers.reserve(threads);
    for (int t = 0; t < threads; ++t) {
        const int y_begin = static_cast<int>(
                static_cast<int64_t>(image_height) * t / threads);
        const int y_end = static_cast<int>(
                static_cast<int64_t>(image_height) * (t + 1) / threads);
        workers.emplace_back([&, y_begin, y_end, t]() {
            if (CPU_COUNT(&mask) > 0)
                sched_setaffinity(0, sizeof(mask), &mask);
            if (!band(y_begin, y_end, locals[t].data())) valid = false;
        });
    }
    for (std::thread& worker : workers) worker.join();
    if (!valid) return false;
    for (const std::vector<float>& local : locals)
        for (size_t i = 0; i < terms; ++i) statistics[i] += local[i];
    return true;
}

bool splat(const float* input, const float* target, const float* supplied_guide,
           int image_width, int image_height, const Config& config,
           std::vector<float>& statistics) {
    return parallel_splat(image_height, config, statistics.data(),
            [&](int y_begin, int y_end, float* local) {
                return splat_float_band(input, target, supplied_guide, image_width,
                                        image_height, y_begin, y_end, config, local);
            });
}

bool splat_rgba8(const unsigned char* input, const unsigned char* target,
                 int image_width, int image_height, const Config& config,
                 std::vector<float>& statistics) {
    return parallel_splat(image_height, config, statistics.data(),
            [&](int y_begin, int y_end, float* local) {
                splat_rgba8_band(input, target, image_width, image_height,
                                 y_begin, y_end, config, local);
                return true;
            });
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
                const int previous = cell_index(px, py, pz, config) * kStatsStride;
                const int center = cell_index(x, y, z, config) * kStatsStride;
                const int next = cell_index(nx, ny, nz, config) * kStatsStride;
                for (int term = 0; term < kStatsStride; ++term) {
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
        const int base = cell * kStatsStride;
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
    workspace.statistics.assign(static_cast<size_t>(cells) * kStatsStride, 0.0f);
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
    workspace.statistics.assign(static_cast<size_t>(cells) * kStatsStride, 0.0f);
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
