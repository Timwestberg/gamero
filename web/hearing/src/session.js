/**
 * Click-to-converse for the browser. The catalog supplies the sink (host send, or a local Kokoro line).
 * This file does not name Ember and does not call chat.send.
 *
 * Optional speech slot (speaker.js, or tool-webkit's SpeechOutput):
 *   speak(text, { onStart }) -> Promise<"ended" | "cancelled">, stop()
 * say(text) plays a reply through it: thinking while it prepares, speaking
 * from the first audible sample, listening again when it ends. Barge-in and
 * end call stop(). A reply that was stopped cannot move the state later.
 */

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
  let speechTicket = 0;
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
    session.partial = "";
    session.phase = "listening";
    session.guardLeft = session.duplex.mode === "half" ? policy.guardMs : 0;
    barge.reset();
    committer.resetUtterance();
  }

  function end() {
    sink.stop();
    stopSpeech();
    session.talkOn = false;
    session.phase = "off";
    session.partial = "";
    session.guardLeft = 0;
    barge.reset();
    committer.resetUtterance();
  }

  function arm() {
    if (options.chatState === "awaiting-you") {
      session.notice = options.answerFirst || "Answer the approval first.";
      return false;
    }
    session.talkOn = true;
    session.explained = true;
    session.notice = null;
    session.partial = "";
    session.phase = "listening";
    session.unavailable = false;
    barge.reset();
    committer.resetUtterance();
    sink.arm();
    return true;
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
    return arm() ? "start" : "refused";
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

  session.feed = ({ frameMs, vadProb, rms = 0.2, text = "" }) => {
    if (!session.talkOn) return null;
    if (session.duplex.mode === "half" && audible()) return null;
    if (session.duplex.mode === "half" && session.guardLeft > 0) {
      session.guardLeft = Math.max(0, session.guardLeft - frameMs);
      return null;
    }
    const emberHot = audible();
    if (session.duplex.mode === "full" && emberHot) {
      const minProb = policy.bargeMinProbability;
      const voicedMs = vadProb >= minProb ? frameMs : 0;
      const span = vadProb >= minProb ? Math.max(frameMs, voicedMs) : voicedMs > 0 ? voicedMs : 0;
      if (barge.frame(span > 0, span > 0 ? span : frameMs)) bargeIn("vad");
    } else if (!emberHot) {
      barge.reset();
    }
    if (session.phase === "listening") session.partial = text;
    const event = committer.observe(vadProb >= 0.5 && rms > 0, frameMs, rms, text, vadProb, emberHot, session.assistantText);
    if (event.decision === EndpointDecision.SEND) {
      const sent = text.trim();
      session.partial = "";
      session.phase = "thinking";
      const next = sink.commit(sent);
      if (next === "speaking" && session.phase === "thinking") session.phase = "speaking";
      return event;
    }
    if (event.decision === EndpointDecision.DROP) {
      session.partial = "";
      session.logs.push(`drop ${event.discard}`);
    }
    return event;
  };

  session.playbackEnded = () => {
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
