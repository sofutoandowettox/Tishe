#pragma once
#include "soloud.h"
#include "soloud_freeverbfilter.h"
#include "soloud_bassboostfilter.h"
#include "soloud_biquadresonantfilter.h"
#include "soloud_echofilter.h"
#include "soloud_lofifilter.h"
#include "soloud_flangerfilter.h"
#include "soloud_wavstream.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>

namespace tishe {
constexpr unsigned RATE = 44100;
// shortcut: tails are capped at three seconds; use decay-aware rendering for longer room effects.
constexpr double TAIL = 3.0;

inline void check(SoLoud::result result) {
    if (result != SoLoud::SO_NO_ERROR) throw std::runtime_error("Could not process audio (code " + std::to_string(result) + ")");
}

struct ExtraEffects {
    float bass = 0, echo = 0, lowpass = 0, lofi = 0, flanger = 0;
};

inline void validate(float speed, float wet, float room, ExtraEffects extra = {}) {
    if (!std::isfinite(speed) || speed < .5f || speed > 1.25f ||
        !std::isfinite(wet) || wet < 0 || wet > 1 ||
        !std::isfinite(room) || room < 0 || room > .95f)
        throw std::runtime_error("Invalid effect parameters");
    for (float value : {extra.bass, extra.echo, extra.lowpass, extra.lofi, extra.flanger})
        if (!std::isfinite(value) || value < 0 || value > 1)
            throw std::runtime_error("Invalid effect parameters");
}

// Member order keeps sources and filters alive until the engine has stopped.
struct Engine {
    SoLoud::FreeverbFilter reverb;
    SoLoud::BassboostFilter bass;
    SoLoud::BiquadResonantFilter lowpass;
    SoLoud::LofiFilter lofi;
    SoLoud::FlangerFilter flanger;
    SoLoud::EchoFilter echo;
    std::unique_ptr<SoLoud::WavStream> track;
    SoLoud::Soloud mixer;
    SoLoud::handle voice = 0;
    float speed = .8f, wet = .3f, room = .7f;
    ExtraEffects extra;
    bool looping = false;
    enum { BASS, LOWPASS, LOFI, FLANGER, ECHO, REVERB };
    explicit Engine(bool offline) {
        check(mixer.init(SoLoud::Soloud::CLIP_ROUNDOFF,
            offline ? SoLoud::Soloud::NULLDRIVER : SoLoud::Soloud::MINIAUDIO, RATE, 1024, 2));
        mixer.setPostClipScaler(1);
        mixer.setGlobalVolume(.7f);
        resetFilters();
    }
    ~Engine() { mixer.deinit(); }
    void parameters(bool smooth) {
        auto parameter = [&](unsigned slot, unsigned id, float value) {
            if (smooth) mixer.fadeFilterParameter(0, slot, id, value, .03);
            else mixer.setFilterParameter(0, slot, id, value);
        };
        parameter(REVERB, SoLoud::FreeverbFilter::WET, wet);
        parameter(REVERB, SoLoud::FreeverbFilter::ROOMSIZE, room);
        parameter(BASS, SoLoud::BassboostFilter::WET, extra.bass);
        parameter(LOWPASS, SoLoud::BiquadResonantFilter::FREQUENCY, 20000 * std::pow(.025f, extra.lowpass));
        parameter(LOWPASS, SoLoud::BiquadResonantFilter::WET, extra.lowpass > 0 ? 1.f : 0.f);
        parameter(LOFI, SoLoud::LofiFilter::WET, extra.lofi);
        parameter(FLANGER, SoLoud::FlangerFilter::WET, extra.flanger);
        parameter(ECHO, SoLoud::EchoFilter::WET, extra.echo);
    }
    void resetFilters() {
        check(bass.setParams(4));
        check(lowpass.setParams(SoLoud::BiquadResonantFilter::LOWPASS, 20000, .707f));
        check(lofi.setParams(8000, 6));
        check(flanger.setParams(.003f, .35f));
        check(echo.setParams(.3f, .4f, .2f));
        check(reverb.setParams(0, room, .5f, 1));
        mixer.setGlobalFilter(BASS, &bass);
        mixer.setGlobalFilter(LOWPASS, &lowpass);
        mixer.setGlobalFilter(LOFI, &lofi);
        mixer.setGlobalFilter(FLANGER, &flanger);
        mixer.setGlobalFilter(ECHO, &echo);
        mixer.setGlobalFilter(REVERB, &reverb);
        parameters(false);
    }
    void load(const char* path) {
        auto candidate = std::make_unique<SoLoud::WavStream>();
        check(candidate->load(path));
        const auto length = candidate->getLength();
        if (!std::isfinite(length) || length <= 0 || length > 3600 ||
            candidate->mChannels < 1 || candidate->mChannels > 2)
            throw std::runtime_error("Choose a mono or stereo track up to one hour long");
        mixer.stopAll();
        track = std::move(candidate);
        resetFilters();
        voice = mixer.play(*track, 1, 0, true);
        if (!voice) throw std::runtime_error("Could not start playback");
        mixer.setAutoStop(voice, false);
        mixer.setLooping(voice, looping);
        check(mixer.setRelativePlaySpeed(voice, speed));
    }
    void effects(float s, float w, float r, ExtraEffects x = {}) {
        validate(s, w, r, x);
        speed = s; wet = w; room = r;
        extra = x;
        if (mixer.isValidVoiceHandle(voice)) check(mixer.setRelativePlaySpeed(voice, speed));
        parameters(true);
    }
    void seek(double seconds) {
        if (!track || !std::isfinite(seconds) || seconds < 0 || seconds > track->getLength())
            throw std::runtime_error("Invalid track position");
        if (!mixer.isValidVoiceHandle(voice)) {
            voice = mixer.play(*track, 1, 0, true);
            mixer.setAutoStop(voice, false);
            mixer.setLooping(voice, looping);
            check(mixer.setRelativePlaySpeed(voice, speed));
        }
        check(mixer.seek(voice, seconds));
        resetFilters();
    }
    void repeat(bool enabled) {
        looping = enabled;
        mixer.setLooping(voice, enabled);
        if (enabled && track && mixer.getStreamPosition(voice) >= track->getLength()) seek(0);
    }
    double position() {
        if (!track) return 0;
        if (!mixer.isValidVoiceHandle(voice)) return track->getLength();
        const auto seconds = std::max(0.0, mixer.getStreamPosition(voice));
        return looping ? std::fmod(seconds, track->getLength()) : std::min(seconds, track->getLength());
    }
    bool playing() {
        if (!mixer.isValidVoiceHandle(voice) || mixer.getPause(voice)) return false;
        if (!looping && mixer.getStreamPosition(voice) >= track->getLength() + speed * TAIL) {
            mixer.setPause(voice, true);
            mixer.setGlobalVolume(0);
            return false;
        }
        return true;
    }
};

inline void u16(std::ostream& out, uint16_t n) {
    const char b[] = {static_cast<char>(n), static_cast<char>(n >> 8)};
    out.write(b, 2);
}
inline void u32(std::ostream& out, uint32_t n) {
    u16(out, static_cast<uint16_t>(n)); u16(out, static_cast<uint16_t>(n >> 16));
}
inline void render(const char* input, const char* output, float speed, float wet, float room, ExtraEffects extra = {}) {
    validate(speed, wet, room, extra);
    Engine engine(true);
    engine.speed = speed; engine.wet = wet; engine.room = room;
    engine.extra = extra;
    engine.load(input);
    const auto frames = static_cast<uint64_t>(std::ceil((engine.track->getLength() / speed + TAIL) * RATE));
    if (frames * 4 > UINT32_MAX - 36) throw std::runtime_error("WAV file is too large");
    std::ofstream out(output, std::ios::binary | std::ios::trunc);
    out.exceptions(std::ios::failbit | std::ios::badbit);
    out.write("RIFF", 4); u32(out, frames * 4 + 36); out.write("WAVEfmt ", 8);
    u32(out, 16); u16(out, 1); u16(out, 2); u32(out, RATE);
    u32(out, RATE * 4); u16(out, 4); u16(out, 16);
    out.write("data", 4); u32(out, frames * 4);
    engine.mixer.setPause(engine.voice, false);
    std::array<short, 2048> block{};
    for (uint64_t done = 0; done < frames;) {
        const auto count = static_cast<unsigned>(std::min<uint64_t>(1024, frames - done));
        engine.mixer.mixSigned16(block.data(), count);
        out.write(reinterpret_cast<const char*>(block.data()), count * 4);
        done += count;
    }
    out.close();
}
}
