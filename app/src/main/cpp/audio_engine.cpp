#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <vector>
#include <deque>
#include <complex>
#include <cmath>
#include <algorithm>

using cf = std::complex<float>;
static constexpr int N = 512, HOP = 256, BINS = N / 2 + 1;
static constexpr float PI = 3.14159265358979f;

struct FFT {
    std::vector<cf> tw; std::vector<int> rev;
    FFT() {
        tw.resize(N / 2); rev.resize(N);
        for (int i = 0; i < N / 2; i++) tw[i] = std::polar(1.0f, -2 * PI * i / N);
        int bits = 0; while ((1 << bits) < N) bits++;
        for (int i = 0; i < N; i++) { int r = 0; for (int b = 0; b < bits; b++) if (i & (1 << b)) r |= 1 << (bits - 1 - b); rev[i] = r; }
    }
    void run(cf *x, bool inv) {
        for (int i = 0; i < N; i++) if (i < rev[i]) std::swap(x[i], x[rev[i]]);
        for (int len = 2; len <= N; len <<= 1) {
            int half = len / 2, step = N / len;
            for (int i = 0; i < N; i += len)
                for (int j = 0; j < half; j++) {
                    cf w = tw[j * step]; if (inv) w = std::conj(w);
                    cf u = x[i + j], v = x[i + j + half] * w;
                    x[i + j] = u + v; x[i + j + half] = u - v;
                }
        }
        if (inv) for (int i = 0; i < N; i++) x[i] /= (float)N;
    }
};

// AI-free adaptive spectral noise suppressor (noise floor tracking + Wiener-style gain)
struct Denoiser {
    FFT fft; float win[N]; float inBuf[N]; float acc[N];
    float noise[BINS]; float gprev[BINS]; int frames = 0;
    std::deque<float> inQ, outQ;
    cf c[N];

    Denoiser() { reset(); }
    void reset() {
        for (int i = 0; i < N; i++) { win[i] = std::sin(PI * (i + 0.5f) / N); inBuf[i] = 0; acc[i] = 0; }
        for (int k = 0; k < BINS; k++) { noise[k] = 0; gprev[k] = 1; }
        frames = 0; inQ.clear(); outQ.assign(HOP, 0.0f);
    }
    void frame(float strength, bool voice, float sr) {
        for (int i = 0; i < N; i++) c[i] = cf(inBuf[i] * win[i], 0);
        fft.run(c, false);
        float alpha = 1.0f + 2.0f * strength;
        float floorG = 0.3f - 0.27f * strength;
        for (int k = 0; k < BINS; k++) {
            float p = std::norm(c[k]);
            if (frames < 20) noise[k] = (noise[k] * frames + p) / (frames + 1);
            else if (p < noise[k]) noise[k] = 0.85f * noise[k] + 0.15f * p;
            else noise[k] *= 1.0006f;
            float g2 = 1.0f - alpha * noise[k] / (p + 1e-12f);
            float g = std::sqrt(std::max(floorG * floorG, std::min(1.0f, g2)));
            if (voice) { float f = k * sr / N; if (f < 100 || f > 4500) g *= 0.35f; }
            g = (g > gprev[k]) ? 0.4f * gprev[k] + 0.6f * g : 0.7f * gprev[k] + 0.3f * g;
            gprev[k] = g;
            c[k] *= g;
            if (k > 0 && k < N / 2) c[N - k] = std::conj(c[k]);
        }
        frames++;
        fft.run(c, true);
        for (int i = 0; i < N; i++) acc[i] += c[i].real() * win[i];
        for (int i = 0; i < HOP; i++) outQ.push_back(acc[i]);
        for (int i = 0; i < N - HOP; i++) acc[i] = acc[i + HOP];
        for (int i = N - HOP; i < N; i++) acc[i] = 0;
    }
    void process(const float *in, float *out, int n, float strength, bool voice, float sr) {
        for (int i = 0; i < n; i++) inQ.push_back(in[i]);
        while ((int)inQ.size() >= HOP) {
            for (int i = 0; i < N - HOP; i++) inBuf[i] = inBuf[i + HOP];
            for (int i = 0; i < HOP; i++) { inBuf[N - HOP + i] = inQ.front(); inQ.pop_front(); }
            frame(strength, voice, sr);
        }
        for (int i = 0; i < n; i++) {
            if (!outQ.empty()) { out[i] = outQ.front(); outQ.pop_front(); } else out[i] = 0;
        }
    }
};

static inline float softLimit(float y) {
    float a = std::fabs(y);
    if (a < 0.8f) return y;
    float r = 0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f);
    return y < 0 ? -r : r;
}

class Engine : public oboe::AudioStreamDataCallback {
public:
    std::shared_ptr<oboe::AudioStream> in, out;
    std::vector<float> inb, procb;
    Denoiser dn;
    std::atomic<float> gain{1.0f}, strength{0.6f};
    std::atomic<bool> voice{false};
    float sr = 48000;

    bool start(bool bluetooth, int inputId, bool sco) {
        auto perf = bluetooth ? oboe::PerformanceMode::None : oboe::PerformanceMode::LowLatency;
        oboe::AudioStreamBuilder ib;
        ib.setDirection(oboe::Direction::Input)
          ->setPerformanceMode(perf)
          ->setSharingMode(oboe::SharingMode::Exclusive)
          ->setFormat(oboe::AudioFormat::Float)
          ->setChannelCount(1)
          ->setSampleRate(48000)
          ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
          ->setInputPreset(sco ? oboe::InputPreset::VoiceCommunication : oboe::InputPreset::VoiceRecognition);
        if (inputId > 0) ib.setDeviceId(inputId);
        if (ib.openStream(in) != oboe::Result::OK) return false;

        oboe::AudioStreamBuilder ob;
        ob.setDirection(oboe::Direction::Output)
          ->setPerformanceMode(perf)
          ->setSharingMode(oboe::SharingMode::Exclusive)
          ->setFormat(oboe::AudioFormat::Float)
          ->setChannelCount(1)
          ->setSampleRate(in->getSampleRate())
          ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
          ->setUsage(sco ? oboe::Usage::VoiceCommunication : oboe::Usage::Media)
          ->setDataCallback(this);
        if (ob.openStream(out) != oboe::Result::OK) { in->close(); in.reset(); return false; }

        sr = (float)in->getSampleRate();
        int burst = out->getFramesPerBurst();
        out->setBufferSizeInFrames(burst * (bluetooth ? 8 : 2));
        inb.assign(8192, 0.0f); procb.assign(8192, 0.0f);
        dn.reset();

        if (in->requestStart() != oboe::Result::OK) return false;
        return out->requestStart() == oboe::Result::OK;
    }

    void stop() {
        if (out) { out->stop(); out->close(); out.reset(); }
        if (in) { in->stop(); in->close(); in.reset(); }
    }

    double latencyMs() {
        if (!out) return -1;
        auto r = out->calculateLatencyMillis();
        double l = r ? r.value() : 0;
        return l + 1000.0 * HOP * 2 / sr;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) override {
        float *o = static_cast<float *>(audioData);
        int n = std::min<int>(numFrames, (int)inb.size());
        int got = 0;
        if (in) { auto r = in->read(inb.data(), n, 0); if (r) got = r.value(); }
        for (int i = got; i < n; i++) inb[i] = 0;
        float s = strength.load(), g = gain.load();
        if (s > 0.01f) dn.process(inb.data(), procb.data(), n, s, voice.load(), sr);
        else for (int i = 0; i < n; i++) procb[i] = inb[i];
        for (int i = 0; i < numFrames; i++) o[i] = (i < n) ? softLimit(procb[i] * g) : 0.0f;
        return oboe::DataCallbackResult::Continue;
    }
};

static Engine *engine = nullptr;

extern "C" {
JNIEXPORT jboolean JNICALL
Java_com_rupom_clearear_NativeAudio_start(JNIEnv *, jobject, jboolean bt, jint inputId, jboolean sco) {
    if (!engine) engine = new Engine();
    engine->stop();
    return engine->start(bt, inputId, sco);
}
JNIEXPORT void JNICALL
Java_com_rupom_clearear_NativeAudio_stop(JNIEnv *, jobject) { if (engine) engine->stop(); }
JNIEXPORT void JNICALL
Java_com_rupom_clearear_NativeAudio_setParams(JNIEnv *, jobject, jfloat s, jboolean v, jfloat g) {
    if (!engine) engine = new Engine();
    engine->strength.store(s); engine->voice.store(v); engine->gain.store(g);
}
JNIEXPORT jdouble JNICALL
Java_com_rupom_clearear_NativeAudio_getLatencyMs(JNIEnv *, jobject) { return engine ? engine->latencyMs() : -1.0; }
}
