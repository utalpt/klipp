// Klipp Lab: MotionCam .mcraw reading, first step – open a file, read its metadata, decode frames (timed) and draw a
// quick preview of one. The decoding is MotionCam's own decoder library (motioncam-decoder, Apache-2.0,
// https://github.com/mirsadm/motioncam-decoder, fetched by CMakeLists.txt at a pinned commit).
#include <jni.h>
#include <android/log.h>
#include <motioncam/Decoder.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <unistd.h>
#include <vector>

#define LOG_TAG "KlippMcraw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    FILE* file = nullptr;
    std::unique_ptr<motioncam::Decoder> decoder;
    std::vector<uint8_t> data;
    nlohmann::json meta;
    std::string error;
    ~Handle() {
        decoder.reset();
        if (file) fclose(file);
    }
};

Handle* get(jlong h) { return reinterpret_cast<Handle*>(h); }

jstring str(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// position (0..3) of each colour in the 2x2 CFA: index 0 R, 1 G (first), 2 G (second), 3 B
bool cfaOrder(const std::string& a, int& r, int& g1, int& g2, int& b) {
    if (a == "rggb") { r = 0; g1 = 1; g2 = 2; b = 3; return true; }
    if (a == "bggr") { b = 0; g1 = 1; g2 = 2; r = 3; return true; }
    if (a == "grbg") { g1 = 0; r = 1; b = 2; g2 = 3; return true; }
    if (a == "gbrg") { g1 = 0; b = 1; r = 2; g2 = 3; return true; }
    return false;
}

} // namespace

extern "C" {

/** Opens [fd] (a dup is taken; the caller keeps its own). 0 on failure – see lastError. */
JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_Mcraw_nativeOpen(JNIEnv* env, jclass, jint fd) {
    auto* h = new Handle();
    int own = dup(fd);
    h->file = own >= 0 ? fdopen(own, "rb") : nullptr;
    if (!h->file) { if (own >= 0) close(own); delete h; return 0; }
    try {
        h->decoder = std::make_unique<motioncam::Decoder>(h->file);
    } catch (const std::exception& e) {
        LOGE("open: %s", e.what());
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_se_gendut_klipp_engine_Mcraw_nativeClose(JNIEnv*, jclass, jlong h) { delete get(h); }

/** Container metadata, the frame timestamps (ns) and the first frame's metadata, as one JSON object. */
JNIEXPORT jstring JNICALL Java_se_gendut_klipp_engine_Mcraw_nativeInfo(JNIEnv* env, jclass, jlong hp) {
    auto* h = get(hp);
    try {
        nlohmann::json out;
        out["container"] = h->decoder->getContainerMetadata();
        const auto& frames = h->decoder->getFrames();
        out["frames"] = frames;
        if (!frames.empty()) {
            nlohmann::json m;
            h->decoder->loadFrameMetadata(frames.front(), m);
            out["firstFrame"] = m;
        }
        out["audioRate"] = h->decoder->audioSampleRateHz();
        out["audioChannels"] = h->decoder->numAudioChannels();
        out["gyro"] = h->decoder->hasGyroData();
        return str(env, out.dump());
    } catch (const std::exception& e) {
        return str(env, std::string("{\"error\":") + nlohmann::json(e.what()).dump() + "}");
    }
}

/** Decodes frame [index] into the handle's buffer. Returns the time it took (ns), or -1. */
JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_Mcraw_nativeDecode(JNIEnv*, jclass, jlong hp, jint index) {
    auto* h = get(hp);
    try {
        const auto& frames = h->decoder->getFrames();
        if (index < 0 || index >= (jint) frames.size()) return -1;
        int64_t t0 = nowNs();
        h->decoder->loadFrame(frames[index], h->data, h->meta);
        return nowNs() - t0;
    } catch (const std::exception& e) {
        LOGE("decode %d: %s", index, e.what());
        return -1;
    }
}

/** Bytes in the last decoded frame (expected width × height × 2). */
JNIEXPORT jlong JNICALL Java_se_gendut_klipp_engine_Mcraw_nativeLastBytes(JNIEnv*, jclass, jlong hp) {
    return (jlong) get(hp)->data.size();
}

/**
 * A quick look at the last decoded frame: each 2×2 CFA block becomes one pixel (half resolution, every [step]th
 * block), black level off, white balance from the frame's as-shot neutral, the camera's colours as they are (no colour
 * matrix), a plain sRGB curve. Only to see that the decoding is right. Fills [out] (ARGB, outW × outH).
 */
JNIEXPORT jboolean JNICALL Java_se_gendut_klipp_engine_Mcraw_nativePreview(JNIEnv* env, jclass, jlong hp, jintArray out,
                                                                          jint outW, jint outH) {
    auto* h = get(hp);
    try {
        const auto& cm = h->decoder->getContainerMetadata();
        int w = h->meta.value("width", 0), ht = h->meta.value("height", 0);
        if (w <= 0 || ht <= 0 || h->data.size() < (size_t) w * ht * 2) return JNI_FALSE;
        std::string arr = cm.value("sensorArrangment", std::string("rggb"));
        int ir, ig1, ig2, ib;
        if (!cfaOrder(arr, ir, ig1, ig2, ib)) return JNI_FALSE;
        std::vector<double> black = cm.value("blackLevel", std::vector<double>{0, 0, 0, 0});
        if (black.size() < 4) black.resize(4, black.empty() ? 0 : black[0]);
        double white = cm.value("whiteLevel", 1023.0);
        std::vector<double> neutral = h->meta.value("asShotNeutral", std::vector<double>{1, 1, 1});
        double gr = neutral.size() >= 3 && neutral[0] > 0 ? neutral[1] / neutral[0] : 1.0;
        double gb = neutral.size() >= 3 && neutral[2] > 0 ? neutral[1] / neutral[2] : 1.0;
        const auto* px = reinterpret_cast<const uint16_t*>(h->data.data());
        std::vector<jint> buf((size_t) outW * outH);
        int bw = w / 2, bh = ht / 2;
        auto curve = [](double v) {
            v = std::clamp(v, 0.0, 1.0);
            double s = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1 / 2.4) - 0.055;
            return (jint) std::lround(s * 255);
        };
        for (int y = 0; y < outH; y++) {
            int by = std::min(bh - 1, (int) ((int64_t) y * bh / outH));
            for (int x = 0; x < outW; x++) {
                int bx = std::min(bw - 1, (int) ((int64_t) x * bw / outW));
                const uint16_t* row0 = px + (size_t) (by * 2) * w + bx * 2;
                const uint16_t* row1 = row0 + w;
                double v[4] = {(double) row0[0], (double) row0[1], (double) row1[0], (double) row1[1]};
                for (int k = 0; k < 4; k++) v[k] = (v[k] - black[k]) / (white - black[k]);
                double r = v[ir] * gr, g = (v[ig1] + v[ig2]) / 2, b = v[ib] * gb;
                buf[(size_t) y * outW + x] = (jint) (0xFF000000u | (curve(r) << 16) | (curve(g) << 8) | curve(b));
            }
        }
        env->SetIntArrayRegion(out, 0, (jsize) buf.size(), buf.data());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("preview: %s", e.what());
        return JNI_FALSE;
    }
}

} // extern "C"
