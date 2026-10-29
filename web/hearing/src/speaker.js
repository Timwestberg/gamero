/**
 * Ember's speech output. It implements the TTS slot the hearing session
 * drives (the same shape tool-webkit's hearing module takes):
 *
 *   speak(text, { voiceId?, onStart? }) -> Promise<"ended" | "cancelled">
 *   stop()                              -> silence within one audio chunk, drop pending synthesis
 *   prepare()                           -> start loading and open audio output (call it from a click)
 *
 * Same speak path as desktop EmberVoice.speakStreaming with Kokoro: the reply
 * is split by speechPieces, each piece is synthesized, edge silence is
 * trimmed and faded (shapeSentence), and 160 ms of silence goes before every
 * piece after the first. Piece N+1 is synthesized while piece N plays.
 *
 * engine: { load(), synthesize(piece, voiceId) -> { samples, sampleRate, synthMs } | null, cancel() }
 * player: { start(), enqueue(samples, { mark }), reached(mark), drained(), flush() }
 */

import { CROSSFADE_MS, SILENCE_TRIM_THRESHOLD, concatSamples, msToSamples, shapeSentence } from "./kokoro-audio.js";
import { DEFAULT_VOICE_ID } from "./kokoro-voices.js";
import { SENTENCE_GAP_MS } from "./engines.js";
import { speechPieces } from "./speech-text.js";

export function createSpeaker({ engine, player, voiceId = DEFAULT_VOICE_ID, gapMs = SENTENCE_GAP_MS, now = defaultNow }) {
  let generation = 0;
  let speaking = false;
  const speaker = {
    voiceId,
    /** Timings of the last reply: ttfaMs, rtf, and one row per piece. */
    lastReport: null,
    async speak(text, options = {}) {
      if (speaking) speaker.stop();
      generation += 1;
      const ticket = generation;
      speaking = true;
      try {
        return await speakTicket(ticket, text, options);
      } finally {
        if (ticket === generation) speaking = false;
      }
    },
    stop() {
      generation += 1;
      speaking = false;
      player.flush();
      engine.cancel();
    },
    /** Opens the audio output inside the caller's user gesture and starts the model download. Errors stay on engine.status. */
    prepare() {
      return Promise.all([engine.load(), player.start()]).catch(() => {});
    },
  };

  async function speakTicket(ticket, text, options) {
    const live = () => ticket === generation;
    const voice = options.voiceId ?? speaker.voiceId;
    const started = now();
    const pieces = speechPieces(text);
    const report = { text, voiceId: voice, pieces: [], ttfaMs: null, rtf: null };
    speaker.lastReport = report;
    if (pieces.length === 0) return "ended";

    await Promise.all([engine.load(), player.start()]);
    if (!live()) return "cancelled";

    let produced = 0;
    let pending = engine.synthesize(pieces[0], voice);
    for (let index = 0; index < pieces.length; index += 1) {
      const take = await pending;
      pending = null;
      if (!live() || take == null) return "cancelled";
      const nextPiece = () => (index + 1 < pieces.length ? engine.synthesize(pieces[index + 1], voice) : null);
      const shaped = shapeSentence(
        take.samples,
        SILENCE_TRIM_THRESHOLD,
        msToSamples(take.sampleRate, CROSSFADE_MS),
        index > 0,
        true,
      );
      report.pieces.push({ chars: pieces[index].length, synthMs: round(take.synthMs), audioMs: round((shaped.length / take.sampleRate) * 1000) });
      if (shaped.length === 0) {
        pending = nextPiece();
        continue;
      }
      const gap = produced > 0 && gapMs > 0 ? new Float32Array(msToSamples(take.sampleRate, gapMs)) : null;
      const mark = `${ticket}:${index}`;
      player.enqueue(gap ? concatSamples([gap, shaped]) : shaped, { mark });
      produced += 1;
      if (!(await player.reached(mark)) || !live()) return "cancelled";
      if (report.ttfaMs == null) {
        report.ttfaMs = round(now() - started);
        options.onStart?.();
      }
      pending = nextPiece();
    }
    if (produced === 0) return "ended";
    const done = await player.drained();
    if (!done || !live()) return "cancelled";
    const synthMs = report.pieces.reduce((sum, piece) => sum + piece.synthMs, 0);
    const audioMs = report.pieces.reduce((sum, piece) => sum + piece.audioMs, 0);
    report.rtf = audioMs > 0 ? round3(synthMs / audioMs) : null;
    return "ended";
  }

  return speaker;
}

function defaultNow() {
  return globalThis.performance?.now() ?? Date.now();
}

function round(ms) {
  return Math.round(ms * 10) / 10;
}

function round3(value) {
  return Math.round(value * 1000) / 1000;
}
