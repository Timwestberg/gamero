#ifndef EMBER_AUDIO_H
#define EMBER_AUDIO_H

#include <stdint.h>

/* Mac voice-processing graph. Playback through this graph is the AEC reference.
 * The read tap is 16 kHz mono float, after voice processing.
 */
#define EMBER_AUDIO_OK 0
#define EMBER_AUDIO_FAILED -1
#define EMBER_AUDIO_MIC_DENIED -2
#define EMBER_AUDIO_VOICE_PROCESSING -3

int ember_audio_start(int sample_rate);
int ember_audio_read(float *dst, int capacity);
int ember_audio_play(const int16_t *pcm, int samples, int sample_rate);
void ember_audio_flush(void);
void ember_audio_stop(void);
int ember_audio_voice_processing(void);
const char *ember_audio_last_error(void);

#endif
