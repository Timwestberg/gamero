/**
 * Click-to-converse for the browser. The page supplies the sink (the catalog's is a local Kokoro line).
 * This file does not name Ember and sends nothing anywhere.
 *
 * Optional speech slot (speaker.js, or tool-webkit's SpeechOutput):
 *   speak(text, { onStart }) -> Promise<"ended" | "cancelled">, stop()
 * say(text) plays a reply through it: thinking while it prepares, speaking
 * from the first audible sample, listening again when it ends. Barge-in and
 * end call stop(). A reply that was stopped cannot move the state later.
 *
 * Microphone frames (listener.js) may carry desktop's own fields:
 *   voicedMs     milliseconds of VAD windows at or above the hearing threshold in this chunk;
 *                the committer's "voiced" and the barge-in span then follow DesktopHearing/TalkLoop
 *   deferCommit  on an endpoint, leave the send to commitTurn(text), which takes the recognizer's
 *                finished text the way desktop's engine.finish() does
 * Hooks: onBargeIn(kind) and onEnd(), so the listener can restart or stop the microphone.
 */

/** Desktop keeps Ember "hot" this long after she stops, for echo screening only. */
export const EMBER_HOT_TAIL_MS = 500;

import {
  BargeInGuard,
  EndpointDecision,
  HearingStates,
  TurnCommitter,
  catalogHearingState,
  chooseDuplex,
} from "./policy.js";

export function createSession(options) {
  const policy = options.policy;
  const sink = options.sink;
  const speech = options.speech || null;
  const notify = options.onChange || (() => {});
  const onBargeIn = options.onBargeIn || (() => {});
  const onEnd = options.onEnd || (() => {});
  let speechTicket = 0;
  let hotUntil = -Infinity;
  const clock = options.now || (() => 0);
  const duplex = chooseDuplex(options.duplexRequest || "auto", options.aecAvailable, options.selfCheck || "absent");
  const barge = new BargeInGuard(policy.bargeSustainMs, policy.bargeGapResetMs);
  const committer = new TurnCommitter({
    silenceMs: policy.silenceMs,
    minSpeechMs: policy.minSpeechMs,
    textStableMs: policy.silenceMs,
    calibrateMs: 300,
    floorMargin: 0.01,
    maxSpeechMs: 15_000,
    barge: {
      sustainMs: policy.bargeSustainMs,
      minProbability: policy.bargeMinProbability,
      echoOverlapPercent: policy.echoOverlapPercent,
      shortWordLimit: policy.shortWordLimit,
      minSpeechMs: policy.minSpeechMs,
    },
  });
  const session = {
    talkOn: false,
    phase: "off",
    partial: "",
    assistantText: "",
    notice: null,
    explained: false,
    duplex,
    logs: [],
    guardLeft: 0,
    get hearingState() {
      return catalogHearingState(
        options.hostPresent !== false,
        session.talkOn,
        false,
        session.phase,
        session.partial,
        session.unavailable === true,
      );
    },
  };

  function audible() {
    return session.phase === "thinking" || session.phase === "speaking";
  }

  function halfBlocks() {
    return session.duplex.mode === "half" && (audible() || session.guardLeft > 0);
  }

  function stopSpeech() {
    speechTicket += 1;
    if (speech) speech.stop();
  }

  function bargeIn(kind) {
    const started = clock();
    sink.stop();
    stopSpeech();
    const flushMs = clock() - started;
    session.logs.push(`barge-in ${kind} flush_ms=${flushMs}`);
    hotUntil = clock() + EMBER_HOT_TAIL_MS;
    onBargeIn(kind);
    session.partial = "";
    session.phase = "listening";
    session.guardLeft = session.duplex.mode === "half" ? policy.guardMs : 0;
    barge.reset();
    committer.resetUtterance();
  }

  function end() {
    sink.stop();
    stopSpeech();
    onEnd();
    session.talkOn = false;
    session.phase = "off";
    session.partial = "";
    session.guardLeft = 0;
    barge.reset();
    committer.resetUtterance();
  }

  function arm() {
    session.talkOn = true;
    session.explained = true;
    session.notice = null;
    session.partial = "";
    session.phase = "listening";
    session.unavailable = false;
    barge.reset();
    committer.resetUtterance();
    sink.arm();
  }

  session.click = () => {
    if (session.talkOn && audible()) {
      bargeIn("press");
      return "barge";
    }
    if (session.talkOn) {
      end();
      return "end";
    }
    arm();
    return "start";
  };

  session.escape = () => {
    if (!session.talkOn) return false;
    if (audible()) bargeIn("press");
    else end();
    return true;
  };

  session.stop = () => {
    if (!session.talkOn) return false;
    end();
    return true;
  };

  /** True while a microphone chunk would be heard: talk is on and half duplex is not holding the mic closed. */
  Object.defineProperty(session, "micOpen", {
    get: () => session.talkOn && !(session.duplex.mode === "half" && (audible() || session.guardLeft > 0)),
  });

  session.feed = ({ frameMs, vadProb, rms = 0.2, text = "", voicedMs, deferCommit = false }) => {
    if (!session.talkOn) return null;
    if (session.duplex.mode === "half" && audible()) return null;
    if (session.duplex.mode === "half" && session.guardLeft > 0) {
      session.guardLeft = Math.max(0, session.guardLeft - frameMs);
      return null;
    }
    const playing = audible();
    const emberHot = playing || clock() < hotUntil;
    const desktopFrame = voicedMs !== undefined;
    if (session.duplex.mode === "full" && playing) {
      const minProb = policy.bargeMinProbability;
      const windowMs = desktopFrame ? voicedMs : vadProb >= minProb ? frameMs : 0;
      const span = vadProb >= minProb ? Math.max(frameMs, windowMs) : windowMs > 0 ? windowMs : 0;
      if (barge.frame(span > 0, span > 0 ? span : frameMs)) bargeIn("vad");
    } else if (!playing) {
      barge.reset();
    }
    if (session.phase === "listening") session.partial = text;
    const voiced = desktopFrame ? voicedMs > 0 : vadProb >= 0.5 && rms > 0;
    const event = committer.observe(voiced, frameMs, rms, text, vadProb, emberHot, session.assistantText);
    if (event.decision === EndpointDecision.SEND) {
      session.partial = "";
      if (deferCommit) return { ...event, pending: true };
      commit(text);
      return event;
    }
    if (event.decision === EndpointDecision.DROP) {
      session.partial = "";
      session.logs.push(`drop ${event.discard}`);
    }
    return event;
  };

  function commit(text) {
    const sent = text.trim();
    session.phase = "thinking";
    const next = sink.commit(sent);
    if (next === "speaking" && session.phase === "thinking") session.phase = "speaking";
  }

  /**
   * Sends a deferred turn with the recognizer's finished text. Blank text is
   * dropped, as desktop only sends a non-blank result. False when nothing was sent.
   */
  session.commitTurn = (text) => {
    if (!session.talkOn || session.phase !== "listening") return false;
    if (!text || text.trim() === "") {
      session.logs.push("drop blank");
      return false;
    }
    commit(text);
    notify();
    return true;
  };

  session.playbackEnded = () => {
    hotUntil = clock() + EMBER_HOT_TAIL_MS;
    if (!session.talkOn) return;
    session.phase = "listening";
    if (session.duplex.mode === "half") session.guardLeft = policy.guardMs;
  };

  session.markSpeaking = (text) => {
    session.assistantText = text || session.assistantText;
    if (session.talkOn) session.phase = "speaking";
  };

  /** Speaks a reply through the speech slot. Resolves with the slot's result. */
  session.say = (text) => {
    session.assistantText = text || session.assistantText;
    if (!speech) {
      session.markSpeaking(text);
      return Promise.resolve("ended");
    }
    speechTicket += 1;
    const ticket = speechTicket;
    if (session.talkOn) session.phase = "thinking";
    const current = () => ticket === speechTicket;
    return speech
      .speak(text, {
        onStart: () => {
          if (!current()) return;
          if (session.talkOn) session.phase = "speaking";
          notify();
        },
      })
      .then(
        (result) => {
          if (current() && result === "ended") {
            session.playbackEnded();
            notify();
          }
          return result;
        },
        (error) => {
          if (current()) {
            session.notice = `Ember could not speak: ${error?.message || error}`;
            session.playbackEnded();
            notify();
          }
          return "cancelled";
        },
      );
  };

  return session;
}

export { HearingStates };
