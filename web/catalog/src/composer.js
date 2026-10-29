/**
 * Catalog wiring. With a host, a finished turn is chat.hearing / chat.send.
 * Without a host, chat stays unavailable and Kokoro reads one fixed line.
 * Ember's replies go through the session's speech slot (hearing/src/speaker.js).
 */

import { createListener, createMicCapture } from "../../hearing/src/listener.js";
import { createSession } from "../../hearing/src/session.js";
import { WEB_MIC_CONSTRAINTS } from "../../hearing/src/media.js";

export const LOCAL_KOKORO_LINE = "Hi, I'm Sam.";
export const PERMISSION_COPY = "Ember hears you on this Mac to turn what you say into a chat message. The recording is not saved.";
export const NO_HOST_NOTICE = "Open Hovel on your Mac to connect…";
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
export function createComposer({ host, speaker, hearing = null, micStream = null, capture = createMicCapture, aecAvailable, selfCheck, now = () => globalThis.performance?.now() ?? 0, onChange }) {
  let listener = null;
  const session = createSession({
    policy: POLICY,
    now,
    aecAvailable,
    selfCheck,
    hostPresent: host != null,
    chatState: host?.chatState,
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
        if (host) host.hearing("press");
      },
      stop() {
        if (host) host.stop();
      },
      commit(text) {
        if (host) {
          host.send(text);
          return "thinking";
        }
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
    /** Ember's reply from the host (or the test page): spoken through the slot. */
    reply(text) {
      return session.say(text);
    },
    permission: PERMISSION_COPY,
    chatNotice: host ? null : NO_HOST_NOTICE,
    micConstraints: WEB_MIC_CONSTRAINTS,
  };
}
