// Klipp Lab: Cinematic RAW Studio (.CRAW) frames for the GPU develop (engine/Craw17u.kt, McrawConvert). Uses only
// matthew777777's decoder (third_party/craw, Apache-2.0: Clip, Reader) to decompress each frame's CFA samples; the
// develop itself (levels, white balance, lens shading, demosaic, colour, log curve) is McrawConvert's shader, as for
// MotionCam RAW. The metadata goes out as JSON with MotionCam's keys (blackLevel, asShotNeutral, lensShadingMap…), so
// the same develop reads both. RGB-cell takes (SPEC 3.8) are not handled here: CrawImport (CPU) develops those.
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "common/Clip.h"
#include "common/Json.h"

namespace {
using craw::tools::Clip;
using craw::tools::RawFrame;

struct Handle {
    Clip clip;
    explicit Handle(int fd) : clip(fd, "CRAW") {}
};

Handle& handle(jlong h) { return *reinterpret_cast<Handle*>(h); }

void fail(JNIEnv* env, const std::exception& e) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, e.what());
}

std::string num(double v) {
    if (!std::isfinite(v)) return "0";
    char b[40];
    std::snprintf(b, sizeof b, "%.9g", v);
    return b;
}

template <class T>
std::string array(const T& v) {
    std::string s = "[";
    bool first = true;
    for (double x : v) { if (!first) s += ","; s += num(x); first = false; }
    return s + "]";
}

const char* cfaName(int cfa) {
    switch (cfa) { case 1: return "grbg"; case 2: return "gbrg"; case 3: return "bggr"; default: return "rggb"; }
}

// asShotNeutral from a frame's metadata (no decode), [1,1,1] when absent
std::vector<double> neutralOf(const std::string& metaJson) {
    const auto fm = craw::json::parseOrEmpty(metaJson);
    const auto& n = fm["asShotNeutral"];
    if (n.isArray() && n.size() == 3) {
        std::vector<double> v{n[0].num(1), n[1].num(1), n[2].num(1)};
        if (v[0] > 0 && v[1] > 0 && v[2] > 0) return v;
    }
    return {1, 1, 1};
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_se_gendut_klipp_engine_Craw17u_nativeOpen(JNIEnv* env, jclass, jint fd) {
    try {
        auto h = std::make_unique<Handle>(fd);
        if (!h->clip.frameCount()) throw std::runtime_error("CRAW has no video frames");
        return reinterpret_cast<jlong>(h.release());
    } catch (const std::exception& e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_se_gendut_klipp_engine_Craw17u_nativeClose(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Handle*>(h);
}

// {"container":{…}, "frames":[ns…], "firstFrame":{width,height,asShotNeutral}, "audioRate", "audioChannels", "rgb"}
extern "C" JNIEXPORT jstring JNICALL
Java_se_gendut_klipp_engine_Craw17u_nativeInfo(JNIEnv* env, jclass, jlong h) {
    try {
        const Clip& c = handle(h).clip;
        const auto& info = c.info();
        const auto [w, hh] = c.frameSize();
        std::string s = "{\"container\":{";
        s += "\"blackLevel\":" + array(info.black);
        s += ",\"whiteLevel\":" + num(info.white);
        s += std::string(",\"sensorArrangment\":\"") + cfaName(info.cfa) + "\"";
        if (info.colorMatrix1.size() == 9) s += ",\"colorMatrix1\":" + array(info.colorMatrix1);
        if (info.colorMatrix2.size() == 9) s += ",\"colorMatrix2\":" + array(info.colorMatrix2);
        if (info.forwardMatrix1.size() == 9) s += ",\"forwardMatrix1\":" + array(info.forwardMatrix1);
        if (info.forwardMatrix2.size() == 9) s += ",\"forwardMatrix2\":" + array(info.forwardMatrix2);
        if (info.calibration1.size() == 9) s += ",\"calibrationMatrix1\":" + array(info.calibration1);
        if (info.calibration2.size() == 9) s += ",\"calibrationMatrix2\":" + array(info.calibration2);
        s += "},\"frames\":[";
        // the times from the chunk table (Reader::frame would read and check every frame's payload)
        bool firstTs = true;
        for (const auto& ch : c.reader().chunks()) {
            if (std::memcmp(ch.fourcc.data(), "FRAM", 4) != 0) continue;
            if (!firstTs) s += ",";
            s += std::to_string(ch.timestampNs);
            firstTs = false;
        }
        const std::string firstMeta = c.reader().frame(0).metaJson;
        s += "],\"firstFrame\":{\"width\":" + std::to_string(w) + ",\"height\":" + std::to_string(hh);
        s += ",\"asShotNeutral\":" + array(neutralOf(firstMeta)) + "}";
        const auto& a = c.audio();
        s += ",\"audioRate\":" + std::to_string(a.format ? a.sampleRate : 0);
        s += ",\"audioChannels\":" + std::to_string(a.format ? a.channels : 0);
        s += std::string(",\"rgb\":") + (c.isRgb() ? "true" : "false") + "}";
        return env->NewStringUTF(s.c_str());
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

// The frame's CFA samples (16-bit, width × height) into [out]; returns its metadata as JSON (MotionCam's keys).
extern "C" JNIEXPORT jstring JNICALL
Java_se_gendut_klipp_engine_Craw17u_nativeDecodeTo(JNIEnv* env, jclass, jlong h, jint index, jobject out) {
    try {
        const Clip& c = handle(h).clip;
        if (c.isRgb()) throw std::runtime_error("RGB-cell CRAW: not a CFA frame");
        const RawFrame raw = c.frameRaw(size_t(index), 2);
        auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
        const size_t bytes = raw.pixels.size() * 2;
        if (!dst || env->GetDirectBufferCapacity(out) < jlong(bytes)) throw std::runtime_error("CRAW frame buffer too small");
        std::memcpy(dst, raw.pixels.data(), bytes);
        std::string s = "{\"asShotNeutral\":" + array(raw.neutral);
        s += ",\"dynamicBlackLevel\":" + array(raw.black);
        s += ",\"dynamicWhiteLevel\":" + num(raw.white);
        const size_t pts = size_t(raw.shadingColumns) * raw.shadingRows;
        if (pts > 0 && raw.shadingGains.size() == pts * 4) {
            s += ",\"lensShadingMapWidth\":" + std::to_string(raw.shadingColumns);
            s += ",\"lensShadingMapHeight\":" + std::to_string(raw.shadingRows);
            s += ",\"lensShadingMap\":[";
            for (int ch = 0; ch < 4; ++ch) {
                if (ch) s += ",";
                s += "[";
                for (size_t k = 0; k < pts; ++k) { if (k) s += ","; s += num(raw.shadingGains[k * 4 + ch]); }
                s += "]";
            }
            s += "]";
        }
        s += "}";
        return env->NewStringUTF(s.c_str());
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

// All the sound, interleaved 16-bit (24-bit takes keep their top 16 bits), as in CrawImport's pcm16 (matthew777777).
// It starts with the first frame, as in CrawImport.
extern "C" JNIEXPORT jshortArray JNICALL
Java_se_gendut_klipp_engine_Craw17u_nativeAudio(JNIEnv* env, jclass, jlong h) {
    try {
        const Clip& c = handle(h).clip;
        const auto& audio = c.audio();
        if (audio.format == 0 || audio.channels <= 0 || audio.frames == 0) return nullptr;
        const size_t samples = size_t(audio.frames) * audio.channels;
        if (samples > size_t(INT32_MAX)) throw std::runtime_error("CRAW sound too long");
        const size_t stride = audio.format == 1 ? 2 : 3;
        const uint64_t bytes = audio.frames * audio.channels * stride;
        const uint64_t payload = c.wavSize() - bytes - (bytes & 1);
        std::vector<int16_t> pcm(samples);
        if (stride == 2) {
            if (c.readWav(payload, reinterpret_cast<uint8_t*>(pcm.data()), samples * 2) != samples * 2) throw std::runtime_error("Short CRAW audio");
        } else {
            std::vector<uint8_t> raw(samples * 3);
            if (c.readWav(payload, raw.data(), raw.size()) != raw.size()) throw std::runtime_error("Short CRAW audio");
            for (size_t i = 0; i < samples; ++i) pcm[i] = int16_t(uint16_t(raw[3 * i + 1]) | uint16_t(raw[3 * i + 2]) << 8);
        }
        jshortArray arr = env->NewShortArray(jsize(samples));
        if (arr) env->SetShortArrayRegion(arr, 0, jsize(samples), pcm.data());
        return arr;
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}
