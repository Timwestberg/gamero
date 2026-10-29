#include <jni.h>
#include "ember_audio.h"

JNIEXPORT jint JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeStart(JNIEnv *env, jclass cls, jint sampleRate) {
    (void)env;
    (void)cls;
    return ember_audio_start(sampleRate);
}

JNIEXPORT jint JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeRead(JNIEnv *env, jclass cls, jfloatArray dst) {
    (void)cls;
    if (!dst) return -1;
    jsize capacity = (*env)->GetArrayLength(env, dst);
    jfloat *data = (*env)->GetFloatArrayElements(env, dst, NULL);
    int n = ember_audio_read(data, capacity);
    (*env)->ReleaseFloatArrayElements(env, dst, data, 0);
    return n;
}

JNIEXPORT jint JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativePlay(JNIEnv *env, jclass cls, jshortArray pcm, jint sampleRate) {
    (void)cls;
    if (!pcm) return -1;
    jsize samples = (*env)->GetArrayLength(env, pcm);
    jshort *data = (*env)->GetShortArrayElements(env, pcm, NULL);
    int code = ember_audio_play(data, samples, sampleRate);
    (*env)->ReleaseShortArrayElements(env, pcm, data, JNI_ABORT);
    return code;
}

JNIEXPORT void JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeFlush(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    ember_audio_flush();
}

JNIEXPORT void JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeStop(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    ember_audio_stop();
}

JNIEXPORT jint JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeVoiceProcessing(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return ember_audio_voice_processing();
}

JNIEXPORT jstring JNICALL Java_org_formulagen_wrapper_voice_EmberAudio_nativeLastError(JNIEnv *env, jclass cls) {
    (void)cls;
    const char *message = ember_audio_last_error();
    return (*env)->NewStringUTF(env, message ? message : "");
}
