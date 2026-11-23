// Klipp Lab: DNG frames for the GPU develop (engine/DngSeq17u.kt, McrawConvert) – a folder of DNGs (PhotonCamera's raw
// video, CinemaDNG) or a single DNG. Reads the DNG itself (Adobe's DNG specification 1.6, a TIFF): the raw image's IFD
// (in IFD 0 or a SubIFD), its strips or tiles, 8 to 16 bits a sample packed most significant bit first, uncompressed
// (lossless JPEG and Linear DNG are not handled yet), and the metadata the develop needs, given as JSON with MotionCam's
// keys (blackLevel, sensorArrangment, colorMatrix1, asShotNeutral, lensShadingMap…), so the same develop reads both.
// The lens shading comes from the GainMap opcodes (OpcodeList1-3), one map per position of the 2×2 colour pattern.
#include <jni.h>

#include <sys/stat.h>
#include <unistd.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

// The whole file read in one go (pread in large blocks): through Android's storage layer that is much faster than
// mapping it, whose page faults each go through the layer (a 16 MB frame took 60-110 ms to decode, mostly that)
struct Map {
    std::vector<uint8_t> buf;
    const uint8_t* p = nullptr;
    size_t n = 0;
    explicit Map(int fd) {
        struct stat st {};
        if (fstat(fd, &st) != 0 || st.st_size <= 8) throw std::runtime_error("DNG: cannot read the file");
        n = size_t(st.st_size);
        buf.resize(n);
        size_t got = 0;
        while (got < n) {
            const ssize_t r = pread(fd, buf.data() + got, std::min<size_t>(n - got, size_t(8) << 20), off_t(got));
            if (r <= 0) throw std::runtime_error("DNG: cannot read the file");
            got += size_t(r);
        }
        p = buf.data();
    }
    Map(const Map&) = delete;
    Map& operator=(const Map&) = delete;
};

struct Entry { uint16_t type = 0; uint32_t count = 0; size_t off = 0; };  // off: where the values are
using Ifd = std::map<uint16_t, Entry>;

struct Tiff {
    const Map& m;
    bool le = true;
    explicit Tiff(const Map& mm) : m(mm) {
        if (m.p[0] == 'I' && m.p[1] == 'I') le = true;
        else if (m.p[0] == 'M' && m.p[1] == 'M') le = false;
        else throw std::runtime_error("not a DNG (TIFF) file");
    }
    void need(size_t o, size_t len) const { if (o > m.n || len > m.n - o) throw std::runtime_error("DNG: truncated"); }
    uint16_t u16(size_t o) const { need(o, 2); return le ? uint16_t(m.p[o] | m.p[o + 1] << 8) : uint16_t(m.p[o] << 8 | m.p[o + 1]); }
    uint32_t u32(size_t o) const {
        need(o, 4);
        return le ? uint32_t(m.p[o]) | uint32_t(m.p[o + 1]) << 8 | uint32_t(m.p[o + 2]) << 16 | uint32_t(m.p[o + 3]) << 24
                  : uint32_t(m.p[o]) << 24 | uint32_t(m.p[o + 1]) << 16 | uint32_t(m.p[o + 2]) << 8 | uint32_t(m.p[o + 3]);
    }
    static size_t typeSize(uint16_t t) {
        switch (t) { case 1: case 2: case 6: case 7: return 1; case 3: case 8: return 2; case 4: case 9: case 11: return 4;
                     case 5: case 10: case 12: return 8; default: return 0; }
    }
    Ifd ifd(size_t o, uint32_t* next = nullptr) const {
        Ifd r;
        const uint16_t n = u16(o);
        for (uint16_t i = 0; i < n; ++i) {
            const size_t e = o + 2 + size_t(i) * 12;
            Entry en; en.type = u16(e + 2); en.count = u32(e + 4);
            const size_t sz = typeSize(en.type) * en.count;
            en.off = sz <= 4 ? e + 8 : u32(e + 8);
            r[u16(e)] = en;
        }
        if (next) *next = u32(o + 2 + size_t(n) * 12);
        return r;
    }
    double num(const Entry& e, uint32_t i) const {
        switch (e.type) {
            case 1: case 7: need(e.off + i, 1); return m.p[e.off + i];
            case 6: need(e.off + i, 1); return int8_t(m.p[e.off + i]);
            case 3: return u16(e.off + 2 * i);
            case 8: return int16_t(u16(e.off + 2 * i));
            case 4: return u32(e.off + 4 * i);
            case 9: return int32_t(u32(e.off + 4 * i));
            case 5: { double d = u32(e.off + 8 * i + 4); return d ? u32(e.off + 8 * i) / d : 0; }
            case 10: { double d = int32_t(u32(e.off + 8 * i + 4)); return d ? int32_t(u32(e.off + 8 * i)) / d : 0; }
            case 11: { uint32_t b = u32(e.off + 4 * i); float f; std::memcpy(&f, &b, 4); return f; }
            case 12: {
                need(e.off + 8 * i, 8);
                uint64_t b = 0;
                for (int k = 0; k < 8; ++k) b |= uint64_t(m.p[e.off + 8 * i + (le ? k : 7 - k)]) << (8 * k);
                double d; std::memcpy(&d, &b, 8); return d;
            }
            default: return 0;
        }
    }
    std::vector<double> nums(const Ifd& f, uint16_t tag) const {
        std::vector<double> v;
        auto it = f.find(tag);
        if (it == f.end()) return v;
        for (uint32_t i = 0; i < it->second.count && i < 4096; ++i) v.push_back(num(it->second, i));
        return v;
    }
    double num1(const Ifd& f, uint16_t tag, double fallback) const {
        auto it = f.find(tag);
        return it == f.end() || it->second.count == 0 ? fallback : num(it->second, 0);
    }
};

std::string fmt(double v) {
    if (!std::isfinite(v)) return "0";
    char b[40];
    std::snprintf(b, sizeof b, "%.9g", v);
    return b;
}

std::string arr(const std::vector<double>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); ++i) { if (i) s += ","; s += fmt(v[i]); }
    return s + "]";
}

// big-endian reads for the opcode lists (always big-endian, whatever the file's order)
uint32_t be32(const uint8_t* p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }
float beF32(const uint8_t* p) { uint32_t b = be32(p); float f; std::memcpy(&f, &b, 4); return f; }

struct Dng {
    Map map;
    Tiff t;
    Ifd ifd0, raw;
    uint32_t width = 0, height = 0, bits = 16, spp = 1, compression = 1, photometric = 0;
    std::string cfa = "rggb";
    int colorAt[4] = {0, 1, 1, 2};  // per 2×2 position: 0 R, 1 G, 2 B

    explicit Dng(int fd) : map(fd), t(map) {
        if (t.u16(2) != 42) throw std::runtime_error("not a DNG (TIFF) file");
        uint32_t next = 0;
        ifd0 = t.ifd(t.u32(4), &next);
        // the raw image: NewSubfileType 0 with CFA (32803) or Linear (34892) data, in IFD 0 or a SubIFD
        std::vector<Ifd> all{ifd0};
        for (double so : t.nums(ifd0, 330)) all.push_back(t.ifd(size_t(so)));
        bool found = false;
        for (auto& f : all) {
            const double kind = t.num1(f, 254, 0);
            const double ph = t.num1(f, 262, 0);
            if (kind == 0 && (ph == 32803 || ph == 34892)) { raw = f; found = true; break; }
        }
        if (!found) throw std::runtime_error("DNG: no raw image in the file");
        width = uint32_t(t.num1(raw, 256, 0));
        height = uint32_t(t.num1(raw, 257, 0));
        bits = uint32_t(t.num1(raw, 258, 16));
        spp = uint32_t(t.num1(raw, 277, 1));
        compression = uint32_t(t.num1(raw, 259, 1));
        photometric = uint32_t(t.num1(raw, 262, 0));
        if (photometric == 34892) throw std::runtime_error("Linear DNG (already demosaiced, e.g. ProRAW) is not supported yet");
        if (compression != 1) throw std::runtime_error("DNG compression " + std::to_string(compression) + " is not supported yet (uncompressed only)");
        if (spp != 1 || bits < 8 || bits > 16) throw std::runtime_error("DNG: unsupported sample layout");
        if (width == 0 || height == 0) throw std::runtime_error("DNG: no image size");
        const auto rep = t.nums(raw, 33421);
        const auto pat = t.nums(raw, 33422);
        if (rep.size() == 2 && rep[0] == 2 && rep[1] == 2 && pat.size() == 4) {
            std::string s;
            for (int i = 0; i < 4; ++i) {
                const int c = int(pat[i]);
                if (c < 0 || c > 2) throw std::runtime_error("DNG: unsupported colour pattern");
                colorAt[i] = c;
                s += "rgb"[c];
            }
            cfa = s;
        } else throw std::runtime_error("DNG: only 2×2 colour patterns are supported");
    }

    // a tag from the raw IFD, else IFD 0 (where cameras put the colour data)
    std::vector<double> any(uint16_t tag) const { auto v = t.nums(raw, tag); return v.empty() ? t.nums(ifd0, tag) : v; }

    // Levels above what the samples can hold (PhotonCamera's "Downscale 4x" writes 10-bit samples with the levels of 16
    // bits: black 4099, white 65535) are scaled down to the samples' range - else everything is under black
    double levelScale() const {
        auto w = any(50717);
        const double maxv = double((1u << bits) - 1);
        return !w.empty() && w[0] > maxv * 1.01 ? maxv / w[0] : 1.0;
    }

    std::vector<double> black4() const {
        auto b = black4raw();
        const double k = levelScale();
        for (double& v : b) v *= k;
        return b;
    }

    std::vector<double> black4raw() const {
        auto b = any(50714);
        const auto rep = any(50713);
        if (b.empty()) return {0, 0, 0, 0};
        if (b.size() == 1) return {b[0], b[0], b[0], b[0]};
        if (b.size() >= 4 && (rep.size() < 2 || (rep[0] == 2 && rep[1] == 2))) return {b[0], b[1], b[2], b[3]};
        double s = 0; for (double v : b) s += v; s /= double(b.size());
        return {s, s, s, s};
    }
    double white() const { auto w = any(50717); return w.empty() ? double((1u << bits) - 1) : w[0] * levelScale(); }

    // lens shading from the GainMap opcodes, as four maps in the develop's channel order (R, first G, second G, B in
    // the raster order of the 2×2 pattern); empty when the file has none in that form
    void shading(std::string& out) const {
        int chanAt[4]; int greens = 0;
        for (int p = 0; p < 4; ++p) chanAt[p] = colorAt[p] == 0 ? 0 : colorAt[p] == 2 ? 3 : 1 + greens++;
        std::vector<float> maps[4];
        uint32_t gw = 0, gh = 0;
        for (uint16_t tag : {uint16_t(51008), uint16_t(51009), uint16_t(51022)}) {
            const Entry* ep = nullptr;
            if (auto a = raw.find(tag); a != raw.end()) ep = &a->second;
            else if (auto b = ifd0.find(tag); b != ifd0.end()) ep = &b->second;
            if (!ep) continue;
            const Entry& e = *ep;
            if (e.off > map.n || e.count > map.n - e.off || e.count < 4) continue;
            const uint8_t* d = map.p + e.off;
            const uint8_t* end = d + e.count;
            const uint32_t count = be32(d);
            const uint8_t* q = d + 4;
            for (uint32_t k = 0; k < count && q + 16 <= end; ++k) {
                const uint32_t id = be32(q), size = be32(q + 12);
                const uint8_t* body = q + 16;
                if (body + size > end) break;
                if (id == 9 && size >= 76) {
                    const uint32_t top = be32(body), left = be32(body + 4), planes = be32(body + 20);
                    const uint32_t rowPitch = be32(body + 24), colPitch = be32(body + 28);
                    const uint32_t mv = be32(body + 32), mh = be32(body + 36), mapPlanes = be32(body + 72);
                    const size_t need = 76 + size_t(mv) * mh * mapPlanes * 4;
                    (void)planes;
                    if (rowPitch == 2 && colPitch == 2 && mapPlanes >= 1 && mv > 1 && mh > 1 && size >= need) {
                        const int pos = int(top % 2) * 2 + int(left % 2);
                        if ((gw == 0 || (gw == mh && gh == mv)) && maps[chanAt[pos]].empty()) {
                            gw = mh; gh = mv;
                            auto& m = maps[chanAt[pos]];
                            m.resize(size_t(mv) * mh);
                            for (size_t i = 0; i < m.size(); ++i) m[i] = beF32(body + 76 + i * 4 * mapPlanes);
                        }
                    }
                }
                q = body + size;
            }
        }
        for (auto& m : maps) if (m.empty()) return;
        out += ",\"lensShadingMapWidth\":" + std::to_string(gw) + ",\"lensShadingMapHeight\":" + std::to_string(gh) + ",\"lensShadingMap\":[";
        for (int c = 0; c < 4; ++c) {
            if (c) out += ",";
            out += "[";
            for (size_t i = 0; i < maps[c].size(); ++i) { if (i) out += ","; out += fmt(maps[c][i]); }
            out += "]";
        }
        out += "]";
    }

    std::string frameMeta() const {
        auto n = any(50728);
        if (n.size() != 3) n = {1, 1, 1};
        std::string s = "{\"asShotNeutral\":" + arr(n);
        s += ",\"dynamicBlackLevel\":" + arr(black4());
        s += ",\"dynamicWhiteLevel\":" + fmt(white());
        shading(s);
        return s + "}";
    }

    std::string info() const {
        std::string s = "{\"width\":" + std::to_string(width) + ",\"height\":" + std::to_string(height);
        s += ",\"bits\":" + std::to_string(bits);
        s += ",\"blackLevel\":" + arr(black4()) + ",\"whiteLevel\":" + fmt(white());
        s += ",\"sensorArrangment\":\"" + cfa + "\"";
        const std::pair<uint16_t, const char*> mats[] = {{50721, "colorMatrix1"}, {50722, "colorMatrix2"}, {50964, "forwardMatrix1"},
            {50965, "forwardMatrix2"}, {50723, "calibrationMatrix1"}, {50724, "calibrationMatrix2"}};
        for (auto& [tag, key] : mats) { auto v = any(tag); if (v.size() == 9) s += std::string(",\"") + key + "\":" + arr(v); }
        auto n = any(50728);
        if (n.size() == 3) s += ",\"asShotNeutral\":" + arr(n);
        auto fr = any(51044);
        if (!fr.empty() && fr[0] > 0) s += ",\"frameRate\":" + fmt(fr[0]);
        auto be = any(50730);
        if (!be.empty()) s += ",\"baselineExposure\":" + fmt(be[0]);
        return s + "}";
    }

    // the raw samples as 16-bit, width × height, into [dst]
    void decode(uint16_t* dst) const {
        const bool tiled = raw.count(324) && raw.count(322);
        const size_t rowBytes = (size_t(width) * bits + 7) / 8;
        auto unpackRow = [&](const uint8_t* src, size_t srcAvail, uint16_t* out, uint32_t n) {
            if (bits == 16) {
                if (srcAvail < size_t(n) * 2) throw std::runtime_error("DNG: truncated image data");
                for (uint32_t x = 0; x < n; ++x) out[x] = t.le ? uint16_t(src[2 * x] | src[2 * x + 1] << 8) : uint16_t(src[2 * x] << 8 | src[2 * x + 1]);
                return;
            }
            if (bits == 8) {
                if (srcAvail < n) throw std::runtime_error("DNG: truncated image data");
                for (uint32_t x = 0; x < n; ++x) out[x] = src[x];
                return;
            }
            if (srcAvail < (size_t(n) * bits + 7) / 8) throw std::runtime_error("DNG: truncated image data");
            if (bits == 10) {
                uint32_t x = 0;
                for (; x + 4 <= n; x += 4, src += 5) {
                    out[x] = uint16_t(src[0] << 2 | src[1] >> 6);
                    out[x + 1] = uint16_t((src[1] & 63) << 4 | src[2] >> 4);
                    out[x + 2] = uint16_t((src[2] & 15) << 6 | src[3] >> 2);
                    out[x + 3] = uint16_t((src[3] & 3) << 8 | src[4]);
                }
                if (x < n) { /* tail: generic below */ uint64_t acc = 0; int have = 0; const uint8_t* s2 = src;
                    for (; x < n; ++x) { while (have < 10) { acc = acc << 8 | *s2++; have += 8; } out[x] = uint16_t(acc >> (have - 10) & 1023); have -= 10; } }
                return;
            }
            if (bits == 12) {
                uint32_t x = 0;
                for (; x + 2 <= n; x += 2, src += 3) {
                    out[x] = uint16_t(src[0] << 4 | src[1] >> 4);
                    out[x + 1] = uint16_t((src[1] & 15) << 8 | src[2]);
                }
                if (x < n) out[x] = uint16_t(src[0] << 4 | src[1] >> 4);
                return;
            }
            // 9, 11, 13-15 bits: a plain big-endian bit stream
            uint64_t acc = 0; int have = 0;
            const uint32_t mask = (1u << bits) - 1;
            for (uint32_t x = 0; x < n; ++x) {
                while (have < int(bits)) { acc = acc << 8 | *src++; have += 8; }
                out[x] = uint16_t(acc >> (have - int(bits)) & mask);
                have -= int(bits);
            }
        };
        if (!tiled) {
            const auto offs = t.nums(raw, 273), lens = t.nums(raw, 279);
            if (offs.empty() || offs.size() != lens.size()) throw std::runtime_error("DNG: no image data");
            const uint32_t rps = uint32_t(t.num1(raw, 278, height));
            uint32_t y = 0;
            for (size_t s = 0; s < offs.size() && y < height; ++s) {
                const size_t o = size_t(offs[s]), l = size_t(lens[s]);
                if (o > map.n || l > map.n - o) throw std::runtime_error("DNG: truncated image data");
                for (uint32_t r = 0; r < rps && y < height; ++r, ++y) {
                    const size_t ro = size_t(r) * rowBytes;
                    if (ro > l) throw std::runtime_error("DNG: truncated image data");
                    unpackRow(map.p + o + ro, l - ro, dst + size_t(y) * width, width);
                }
            }
            if (y < height) throw std::runtime_error("DNG: image data shorter than the picture");
        } else {
            const uint32_t tw = uint32_t(t.num1(raw, 322, 0)), th = uint32_t(t.num1(raw, 323, 0));
            const auto offs = t.nums(raw, 324), lens = t.nums(raw, 325);
            if (!tw || !th) throw std::runtime_error("DNG: bad tiles");
            const uint32_t across = (width + tw - 1) / tw, down = (height + th - 1) / th;
            if (offs.size() < size_t(across) * down || lens.size() != offs.size()) throw std::runtime_error("DNG: missing tiles");
            const size_t tileRow = (size_t(tw) * bits + 7) / 8;
            std::vector<uint16_t> row(tw);
            for (uint32_t ty = 0; ty < down; ++ty) for (uint32_t tx = 0; tx < across; ++tx) {
                const size_t k = size_t(ty) * across + tx;
                const size_t o = size_t(offs[k]), l = size_t(lens[k]);
                if (o > map.n || l > map.n - o) throw std::runtime_error("DNG: truncated tile");
                for (uint32_t r = 0; r < th; ++r) {
                    const uint32_t y = ty * th + r;
                    if (y >= height) break;
                    const size_t ro = size_t(r) * tileRow;
                    if (ro > l) throw std::runtime_error("DNG: truncated tile");
                    unpackRow(map.p + o + ro, l - ro, row.data(), tw);
                    const uint32_t x0 = tx * tw, n = std::min(tw, width - x0);
                    std::memcpy(dst + size_t(y) * width + x0, row.data(), size_t(n) * 2);
                }
            }
        }
    }
};

void fail(JNIEnv* env, const std::exception& e) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass("java/lang/IllegalStateException");
    if (c) env->ThrowNew(c, e.what());
}

}  // namespace

// The file's raw image and colour data as JSON (width, height, bits, blackLevel, whiteLevel, sensorArrangment, the
// matrices, asShotNeutral, frameRate, baselineExposure); throws with the reason when it can't be developed.
extern "C" JNIEXPORT jstring JNICALL
Java_se_gendut_klipp_engine_DngSeq17u_nativeInfo(JNIEnv* env, jclass, jint fd) {
    try { Dng d(fd); return env->NewStringUTF(d.info().c_str()); } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

// The raw samples (16-bit, width × height) into [out]; returns the frame's metadata as JSON (MotionCam's keys).
extern "C" JNIEXPORT jstring JNICALL
Java_se_gendut_klipp_engine_DngSeq17u_nativeDecodeTo(JNIEnv* env, jclass, jint fd, jobject out) {
    try {
        Dng d(fd);
        auto* dst = static_cast<uint16_t*>(env->GetDirectBufferAddress(out));
        if (!dst || env->GetDirectBufferCapacity(out) < jlong(size_t(d.width) * d.height * 2)) throw std::runtime_error("DNG frame buffer too small");
        d.decode(dst);
        return env->NewStringUTF(d.frameMeta().c_str());
    } catch (const std::exception& e) { fail(env, e); return nullptr; }
}
