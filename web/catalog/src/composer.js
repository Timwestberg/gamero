/**
 * Catalog wiring. With a host, a finished turn is chat.hearing / chat.send.
 * Without a host, chat stays unavailable and Kokoro reads one fixed line.
 */

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

export function createComposer({ host, player, aecAvailable, selfCheck, now }) {
  const session = createSession({
    policy: POLICY,
    now,
    aecAvailable,
    selfCheck,
    hostPresent: host != null,
    chatState: host?.chatState,
    sink: {
      arm() {
        if (host) host.hearing("press");
      },
      stop() {
        player.stop();
        if (host) host.stop();
      },
      commit(text) {
        if (host) {
          host.send(text);
          return "thinking";
        }
        player.speak(LOCAL_KOKORO_LINE);
        return "speaking";
      },
    },
  });
  return {
    session,
    permission: PERMISSION_COPY,
    chatNotice: host ? null : NO_HOST_NOTICE,
    micConstraints: WEB_MIC_CONSTRAINTS,
  };
}
