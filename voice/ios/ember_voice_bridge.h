#ifndef EMBER_VOICE_BRIDGE_H_
#define EMBER_VOICE_BRIDGE_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Return 1 to keep generating. The sample pointer is only valid during the call. */
typedef int32_t (*EmberVoiceSamplesFn)(const float *samples, int32_t n, void *arg);

/*
 * Sherpa-onnx PocketTTS. model_dir holds the int8 pocket files from
 * sherpa-onnx-pocket-tts-int8-2026-01-26. voice_wav is the 24 kHz reference.
 * Returns NULL when the xcframework or the files are missing.
 * This is not frame-streaming decode: sherpa-onnx 2.0.0 finishes the language
 * model for a piece before the first callback.
 */
void *ember_voice_sherpa_create(const char *model_dir, const char *voice_wav, int32_t threads);
void ember_voice_sherpa_destroy(void *engine);
int32_t ember_voice_sherpa_sample_rate(void *engine);
/* Milliseconds to the first audio callback of the last speak, and how many samples it produced. */
double ember_voice_sherpa_last_ttfa_ms(void *engine);
int32_t ember_voice_sherpa_last_samples(void *engine);
int32_t ember_voice_sherpa_speak(
    void *engine,
    const char *text,
    int32_t max_frames,
    int32_t num_steps,
    float temperature,
    int32_t seed,
    int32_t chunk_size,
    EmberVoiceSamplesFn callback,
    void *arg);

/* 1 when this build links the sherpa-onnx 2.0.0 xcframework, 0 when the sherpa calls here are stubs. */
int32_t ember_voice_sherpa_linked(void);

/*
 * Sherpa-onnx Kokoro 82M v1.0, the Mac chat voice. model, voices and tokens
 * are files. lexicon names one file, or several joined with commas; an earlier
 * file's pronunciation wins. Returns NULL when the xcframework is not linked
 * or a file does not load.
 */
void *ember_voice_kokoro_create(
    const char *model,
    const char *voices,
    const char *tokens,
    const char *lexicon,
    int32_t threads);
void ember_voice_kokoro_destroy(void *engine);
int32_t ember_voice_kokoro_sample_rate(void *engine);
/*
 * One sentence in the voice at speaker slot sid. The callback may return 0 to
 * stop early. Returns 1 when audio was produced. A second speak while one is
 * running returns 0.
 */
int32_t ember_voice_kokoro_speak(
    void *engine,
    const char *text,
    int32_t sid,
    float speed,
    EmberVoiceSamplesFn callback,
    void *arg);

/* Optional FluidAudio PocketTTS backend. Returns NULL when FluidAudio is not linked. */
void *ember_voice_fluid_create(const char *voice_wav, float temperature, int32_t seed);
void ember_voice_fluid_destroy(void *engine);
int32_t ember_voice_fluid_sample_rate(void *engine);
double ember_voice_fluid_last_ttfa_ms(void *engine);
int32_t ember_voice_fluid_last_samples(void *engine);
int32_t ember_voice_fluid_speak(
    void *engine,
    const char *text,
    int32_t max_frames,
    int32_t seed,
    EmberVoiceSamplesFn callback,
    void *arg);

#ifdef __cplusplus
}
#endif

#endif
