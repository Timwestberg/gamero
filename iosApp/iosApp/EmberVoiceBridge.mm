// The app's copy of the voice module's C bridge (voice/ios/ember_voice_bridge.h), which Kotlin calls for Kokoro.
// With the sherpa-onnx headers on the search path (Configuration/Voice.local.xcconfig, written by
// scripts/ios-voice.sh) it is the real engine; without them every engine is NULL and the app speaks with the
// system voice.
#include "../../voice/ios/EmberVoiceSherpa.mm"

// FluidAudio is a backend of the voice bench only. The app never links it, so its calls answer "not here".
extern "C" void *ember_voice_fluid_create(const char *voice_wav, float temperature, int32_t seed) {
    (void)voice_wav; (void)temperature; (void)seed;
    return NULL;
}
extern "C" void ember_voice_fluid_destroy(void *engine) { (void)engine; }
extern "C" int32_t ember_voice_fluid_sample_rate(void *engine) { (void)engine; return 0; }
extern "C" double ember_voice_fluid_last_ttfa_ms(void *engine) { (void)engine; return 0; }
extern "C" int32_t ember_voice_fluid_last_samples(void *engine) { (void)engine; return 0; }
extern "C" int32_t ember_voice_fluid_speak(
    void *engine, const char *text, int32_t max_frames, int32_t seed, EmberVoiceSamplesFn callback, void *arg) {
    (void)engine; (void)text; (void)max_frames; (void)seed; (void)callback; (void)arg;
    return 0;
}
