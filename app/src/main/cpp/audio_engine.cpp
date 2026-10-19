#include <jni.h>
#include <memory>
#include "render.h"

namespace {
template<class F, class R> R guarded(JNIEnv* env, R fallback, F action) {
    try { return action(); }
    catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return fallback;
    }
}
struct Utf {
    JNIEnv* env; jstring value; const char* text;
    Utf(JNIEnv* e, jstring s) : env(e), value(s), text(e->GetStringUTFChars(s, nullptr)) {
        if (!text) throw std::runtime_error("Could not read the file path");
    }
    ~Utf() { env->ReleaseStringUTFChars(value, text); }
};
tishe::Engine& get(jlong pointer) {
    if (!pointer) throw std::runtime_error("Audio engine is closed");
    return *reinterpret_cast<tishe::Engine*>(pointer);
}
}

extern "C" JNIEXPORT jlong JNICALL Java_app_tishe_audio_AudioEngine_create(JNIEnv* env, jobject, jboolean offline) {
    return guarded(env, jlong(0), [&] { return reinterpret_cast<jlong>(new tishe::Engine(offline)); });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_destroy(JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<tishe::Engine*>(pointer);
}
extern "C" JNIEXPORT jdouble JNICALL Java_app_tishe_audio_AudioEngine_load(JNIEnv* env, jobject, jlong pointer, jstring path) {
    return guarded(env, 0.0, [&] {
        Utf file(env, path); auto& e = get(pointer); e.load(file.text); return e.track->getLength();
    });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_setEffects(JNIEnv* env, jobject, jlong pointer, jfloat speed, jfloat wet, jfloat room,
        jfloat bass, jfloat echo, jfloat lowpass, jfloat lofi, jfloat flanger) {
    guarded(env, false, [&] { get(pointer).effects(speed, wet, room, {bass, echo, lowpass, lofi, flanger}); return true; });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_setRepeat(JNIEnv* env, jobject, jlong pointer, jboolean enabled) {
    guarded(env, false, [&] { get(pointer).repeat(enabled); return true; });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_setPlaying(JNIEnv* env, jobject, jlong pointer, jboolean playing) {
    guarded(env, false, [&] {
        auto& e = get(pointer);
        if (playing && (!e.mixer.isValidVoiceHandle(e.voice) ||
            (!e.looping && e.mixer.getStreamPosition(e.voice) >= e.track->getLength()))) e.seek(0);
        e.mixer.setPause(e.voice, !playing);
        e.mixer.setGlobalVolume(playing ? .7f : 0.f); return true;
    });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_seek(JNIEnv* env, jobject, jlong pointer, jdouble seconds) {
    guarded(env, false, [&] { get(pointer).seek(seconds); return true; });
}
extern "C" JNIEXPORT jdouble JNICALL Java_app_tishe_audio_AudioEngine_position(JNIEnv* env, jobject, jlong pointer) {
    return guarded(env, 0.0, [&] {
        auto& e = get(pointer);
        return e.position();
    });
}
extern "C" JNIEXPORT jboolean JNICALL Java_app_tishe_audio_AudioEngine_isPlaying(JNIEnv* env, jobject, jlong pointer) {
    return guarded(env, jboolean(false), [&] {
        auto& e = get(pointer);
        return jboolean(e.playing());
    });
}
extern "C" JNIEXPORT void JNICALL Java_app_tishe_audio_AudioEngine_renderNative(JNIEnv* env, jclass, jstring input, jstring output, jfloat speed, jfloat wet, jfloat room,
        jfloat bass, jfloat echo, jfloat lowpass, jfloat lofi, jfloat flanger) {
    guarded(env, false, [&] {
        Utf in(env, input), out(env, output);
        tishe::render(in.text, out.text, speed, wet, room, {bass, echo, lowpass, lofi, flanger}); return true;
    });
}
