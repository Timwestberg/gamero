/**
 * Catalog voice wiring: a finished turn makes Kokoro read one fixed line, and replies go through the
 * session's speech slot (hearing/src/speaker.js). The page talks to no host.
 */

import { createListener, createMicCapture } from "../../hearing/src/listener.js";
import { createSession } from "../../hearing/src/session.js";
import { WEB_MIC_CONSTRAINTS } from "../../hearing/src/media.js";

export const LOCAL_KOKORO_LINE = "Hi, I'm Sam.";
export const PERMISSION_COPY = "Ember hears you on this Mac to turn what you say into a chat message. The recording is not saved.";
export const MIC_SETTINGS_NOTICE = "Turn the microphone on for Gamero Adventures in System Settings.";

export const POLICY = {
  silenceMs: 1000,
  minSpeechMs: 240,
  guardMs: 300,
  bargeSustainMs: 250,
  bargeMinProbability: 0.5,
  bargeGapResetMs: 240,
  echoOverlapPercent: 60,
  shortWordLimit: 2,
};

/**
 * speaker: { speak(text, { onStart }) -> Promise<"ended" | "cancelled">, stop(), prepare?() }.
 * prepare() runs on the talk click, so the audio output starts inside a user gesture.
 * hearing (hearing-client.js) and micStream: the real microphone. The talk click opens a 16 kHz
 * capture and starts the hearing models; chunks are ignored until they are ready.
 */
export function createComposer({ speaker, hearing = null, micStream = null, capture = createMicCapture, aecAvailable, selfCheck, now = () => globalThis.performance?.now() ?? 0, onChange }) {
  let listener = null;
  let heard = null;
  const session = createSession({
    policy: POLICY,
    now,
    aecAvailable,
    selfCheck,
    hostPresent: false,
    speech: speaker,
    onChange,
    onBargeIn: () => listener?.restartWithPreRoll(),
    onEnd: () => listener?.stop(),
    sink: {
      arm() {
        speaker?.prepare?.();
        if (listener && micStream) {
          hearing.load().catch(() => onChange?.());
          listener.start(capture(micStream)).catch((error) => {
            session.notice = `The microphone did not start: ${error?.message || error}`;
            onChange?.();
          });
        }
      },
      stop() {},
      commit(text) {
        heard = text;
        session.say(LOCAL_KOKORO_LINE);
        return "thinking";
      },
    },
  });
  if (hearing && micStream) {
    listener = createListener({ session, hearing, ready: () => hearing.status === "ready", onFrame: () => onChange?.() });
  }
  return {
    session,
    listener,
    /** Ember's reply from the test page: spoken through the slot. */
    reply(text) {
      return session.say(text);
    },
    /** The last finished turn's text, or null before one. */
    get heard() {
      return heard;
    },
    permission: PERMISSION_COPY,
    micConstraints: WEB_MIC_CONSTRAINTS,
  };
}
