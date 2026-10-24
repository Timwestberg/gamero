#import "ember_voice_bridge.h"

#import <stdio.h>
#import <string>
#import <string.h>
#import <time.h>

// Header from the sherpa-onnx 2.0.0 iOS xcframework. The JVM jar in this repo
// is that same release. Include the directory that contains c-api.h.
#import "c-api.h"

struct EmberSherpaEngine {
    const SherpaOnnxOfflineTts *tts;
    const SherpaOnnxWave *wave;
    std::string lm_flow;
    std::string lm_main;
    std::string encoder;
    std::string decoder;
    std::string conditioner;
    std::string vocab;
    std::string scores;
    double last_ttfa_ms;
    int32_t last_samples;
    double reference_seconds;
};

struct EmberCallbackBox {
    EmberVoiceSamplesFn fn;
    void *arg;
    int got;
    double started_ms;
    double first_ms;
    int32_t samples;
};

static double ember_now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1000000.0;
}

static int32_t ember_progress(const float *samples, int32_t n, float progress, void *arg) {
    (void)progress;
    EmberCallbackBox *box = (EmberCallbackBox *)arg;
    if (samples == NULL || n <= 0) return 1;
    if (box->first_ms < 0) box->first_ms = ember_now_ms();
    box->samples += n;
    box->got = 1;
    if (box->fn == NULL) return 1;
    return box->fn(samples, n, box->arg);
}

static std::string ember_join(const char *dir, const char *name) {
    std::string path = dir ? dir : "";
    if (!path.empty() && path.back() != '/') path.push_back('/');
    path += name;
    return path;
}

extern "C" void *ember_voice_sherpa_create(const char *model_dir, const char *voice_wav, int32_t threads) {
    if (model_dir == NULL || voice_wav == NULL) return NULL;
    SherpaOnnxOfflineTtsConfig config;
    memset(&config, 0, sizeof(config));
    EmberSherpaEngine *engine = new EmberSherpaEngine();
    engine->lm_flow = ember_join(model_dir, "lm_flow.int8.onnx");
    engine->lm_main = ember_join(model_dir, "lm_main.int8.onnx");
    engine->encoder = ember_join(model_dir, "encoder.onnx");
    engine->decoder = ember_join(model_dir, "decoder.int8.onnx");
    engine->conditioner = ember_join(model_dir, "text_conditioner.onnx");
    engine->vocab = ember_join(model_dir, "vocab.json");
    engine->scores = ember_join(model_dir, "token_scores.json");
    config.model.pocket.lm_flow = engine->lm_flow.c_str();
    config.model.pocket.lm_main = engine->lm_main.c_str();
    config.model.pocket.encoder = engine->encoder.c_str();
    config.model.pocket.decoder = engine->decoder.c_str();
    config.model.pocket.text_conditioner = engine->conditioner.c_str();
    config.model.pocket.vocab_json = engine->vocab.c_str();
    config.model.pocket.token_scores_json = engine->scores.c_str();
    config.model.pocket.voice_embedding_cache_capacity = 50;
    config.model.num_threads = threads > 0 ? threads : 2;
    config.model.provider = "cpu";
    config.model.debug = 0;
    const SherpaOnnxOfflineTts *tts = SherpaOnnxCreateOfflineTts(&config);
    if (tts == NULL) {
        delete engine;
        return NULL;
    }
    const SherpaOnnxWave *wave = SherpaOnnxReadWave(voice_wav);
    if (wave == NULL || wave->sample_rate <= 0 || wave->num_samples <= 0) {
        if (wave != NULL) SherpaOnnxFreeWave(wave);
        SherpaOnnxDestroyOfflineTts(tts);
        delete engine;
        return NULL;
    }
    double seconds = (double)wave->num_samples / (double)wave->sample_rate;
    if (seconds < 1.0 || seconds > 10.0005) {
        fprintf(stderr, "voice clip is too %s (%.2f s). It needs to be between 1 and 10 seconds.\n",
                seconds < 1.0 ? "short" : "long", seconds);
        SherpaOnnxFreeWave(wave);
        SherpaOnnxDestroyOfflineTts(tts);
        delete engine;
        return NULL;
    }
    engine->tts = tts;
    engine->wave = wave;
    engine->last_ttfa_ms = 0;
    engine->last_samples = 0;
    engine->reference_seconds = seconds;
    return engine;
}

extern "C" void ember_voice_sherpa_destroy(void *engine) {
    if (engine == NULL) return;
    EmberSherpaEngine *sherpa = (EmberSherpaEngine *)engine;
    SherpaOnnxFreeWave(sherpa->wave);
    SherpaOnnxDestroyOfflineTts(sherpa->tts);
    delete sherpa;
}

extern "C" int32_t ember_voice_sherpa_sample_rate(void *engine) {
    if (engine == NULL) return 0;
    return SherpaOnnxOfflineTtsSampleRate(((EmberSherpaEngine *)engine)->tts);
}

extern "C" double ember_voice_sherpa_last_ttfa_ms(void *engine) {
    if (engine == NULL) return 0;
    return ((EmberSherpaEngine *)engine)->last_ttfa_ms;
}

extern "C" int32_t ember_voice_sherpa_last_samples(void *engine) {
    if (engine == NULL) return 0;
    return ((EmberSherpaEngine *)engine)->last_samples;
}

extern "C" int32_t ember_voice_sherpa_speak(
    void *engine,
    const char *text,
    int32_t max_frames,
    int32_t num_steps,
    float temperature,
    int32_t seed,
    int32_t chunk_size,
    EmberVoiceSamplesFn callback,
    void *arg) {
    if (engine == NULL || text == NULL) return 0;
    EmberSherpaEngine *sherpa = (EmberSherpaEngine *)engine;
    char extra[256];
    int chars = text == NULL ? 0 : (int)strlen(text);
    int sentence = chars > 120 ? chars : 120;
    snprintf(
        extra,
        sizeof(extra),
        "{\"temperature\":%.4f,\"chunk_size\":%d,\"seed\":%d,\"max_frames\":%d,\"max_char_in_sentence\":%d,\"max_reference_audio_len\":%.4f}",
        temperature,
        chunk_size,
        seed,
        max_frames,
        sentence,
        sherpa->reference_seconds);
    SherpaOnnxGenerationConfig gen;
    memset(&gen, 0, sizeof(gen));
    gen.reference_audio = sherpa->wave->samples;
    gen.reference_audio_len = sherpa->wave->num_samples;
    gen.reference_sample_rate = sherpa->wave->sample_rate;
    gen.speed = 1.0f;
    gen.num_steps = num_steps;
    gen.extra = extra;
    EmberCallbackBox box;
    box.fn = callback;
    box.arg = arg;
    box.got = 0;
    box.started_ms = ember_now_ms();
    box.first_ms = -1;
    box.samples = 0;
    const SherpaOnnxGeneratedAudio *audio =
        SherpaOnnxOfflineTtsGenerateWithConfig(sherpa->tts, text, &gen, ember_progress, &box);
    int ok = audio != NULL && audio->n > 0;
    if (box.first_ms >= 0) sherpa->last_ttfa_ms = box.first_ms - box.started_ms;
    else sherpa->last_ttfa_ms = ember_now_ms() - box.started_ms;
    sherpa->last_samples = audio != NULL ? audio->n : box.samples;
    if (audio != NULL) SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    return ok ? 1 : 0;
}
