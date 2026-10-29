/**
 * The browser port of gamero voice/commonMain VoiceConversation, TurnCommitter,
 * and BargeInGuard. The JSON fixture is the contract. A mismatch fails the test.
 */

export const HearingStates = {
  OFF: "off",
  REQUESTING: "requesting",
  LISTENING: "listening",
  HEARING: "hearing",
  THINKING: "thinking",
  MUTED: "muted",
  UNAVAILABLE: "unavailable",
};

export const HearingActions = {
  PRESS: "press",
  RELEASE: "release",
  HANDS_FREE: "handsFree",
  MUTE: "mute",
  UNMUTE: "unmute",
  OFF: "off",
};

export const DiscardReason = {
  NO_VAD_SPEECH: "no_vad_speech",
  SELF_ECHO: "self_echo",
  TOO_SHORT: "too_short",
};

export const EndpointReason = {
  VAD_SILENCE: "vad_silence",
  TEXT_STABLE: "text_stable",
  CAP: "cap",
};

export const EndpointDecision = {
  LISTEN: "listen",
  SEND: "send",
  DROP: "drop",
};

export function reduceHearing(state, action, hostPresent, permissionGranted) {
  if (!hostPresent) return { state: HearingStates.UNAVAILABLE, requestMicrophone: false };
  let next = state;
  if (action === HearingActions.OFF || action === HearingActions.RELEASE) next = HearingStates.OFF;
  else if (action === HearingActions.MUTE) {
    if (state !== HearingStates.OFF && state !== HearingStates.UNAVAILABLE && state !== HearingStates.REQUESTING) {
      next = HearingStates.MUTED;
    }
  } else if (action === HearingActions.UNMUTE) {
    if (state === HearingStates.MUTED) next = HearingStates.LISTENING;
  } else if (action === HearingActions.PRESS || action === HearingActions.HANDS_FREE) {
    next = permissionGranted ? HearingStates.LISTENING : HearingStates.REQUESTING;
  }
  const ask = action === HearingActions.PRESS || action === HearingActions.HANDS_FREE;
  return { state: next, requestMicrophone: ask && !permissionGranted };
}

export function hearingWithoutHost() {
  return reduceHearing(HearingStates.OFF, HearingActions.PRESS, false, false);
}

export function catalogHearingState(hostPresent, armed, muted, phase, partial, unavailable) {
  if (!hostPresent || unavailable) return HearingStates.UNAVAILABLE;
  if (!armed) return HearingStates.OFF;
  if (muted) return HearingStates.MUTED;
  if (phase === "thinking") return HearingStates.THINKING;
  if (phase === "listening" || phase === "speaking") {
    return partial && partial.trim() !== "" ? HearingStates.HEARING : HearingStates.LISTENING;
  }
  return HearingStates.OFF;
}

export function speechTokens(text) {
  return text.toLowerCase().split(/[^a-z0-9']+/).filter((token) => token.length > 0);
}

function mostlyEcho(words, emberText, overlapPercent) {
  if (words.length === 0) return false;
  const ember = new Set(speechTokens(emberText));
  if (ember.size === 0) return false;
  const overlap = words.filter((word) => ember.has(word)).length;
  return Math.floor((overlap * 100) / words.length) >= overlapPercent;
}

export function screenHeardTurn(text, vadSpeechMs, maxProb, emberHot, emberText, barge) {
  const words = speechTokens(text);
  if (emberHot) {
    const strong = vadSpeechMs >= barge.sustainMs && maxProb >= barge.minProbability;
    if (words.length >= 1 && words.length <= barge.shortWordLimit && !strong) return DiscardReason.TOO_SHORT;
    if (!strong) return DiscardReason.NO_VAD_SPEECH;
    if (mostlyEcho(words, emberText, barge.echoOverlapPercent)) return DiscardReason.SELF_ECHO;
    return null;
  }
  if (vadSpeechMs < barge.minSpeechMs) return DiscardReason.NO_VAD_SPEECH;
  return null;
}

export function chooseDuplex(request, aecAvailable, selfCheck) {
  if (request === "half") return { mode: "half", reason: "forced" };
  if (!aecAvailable) return { mode: "half", reason: "aec-unavailable" };
  if (selfCheck === "fail") return { mode: "half", reason: "self-check-failed" };
  if (selfCheck === "pass") return { mode: "full", reason: "self-check-passed" };
  return { mode: "full", reason: "aec-unverified" };
}

export class BargeInGuard {
  constructor(sustainMs, gapResetMs) {
    this.sustainMs = sustainMs;
    this.gapResetMs = gapResetMs;
    this.reset();
  }

  reset() {
    this.runMs = 0;
    this.gapMs = 0;
    this.armed = true;
  }

  frame(voiced, frameMs) {
    if (frameMs <= 0 || !this.armed) return false;
    if (voiced) {
      this.gapMs = 0;
      this.runMs += frameMs;
      if (this.runMs >= this.sustainMs) {
        this.armed = false;
        this.runMs = 0;
        return true;
      }
      return false;
    }
    this.gapMs += frameMs;
    if (this.gapMs >= this.gapResetMs) {
      this.runMs = 0;
      this.gapMs = 0;
    }
    return false;
  }
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export class TurnCommitter {
  constructor(options) {
    this.silenceMs = options.silenceMs;
    this.minSpeechMs = options.minSpeechMs;
    this.textStableMs = options.textStableMs;
    this.calibrateMs = options.calibrateMs;
    this.floorMargin = options.floorMargin;
    this.maxSpeechMs = options.maxSpeechMs;
    this.barge = options.barge;
    this.floorSamples = [];
    this.calibratedMs = 0;
    this.floor = 0;
    this.floorReady = false;
    this.resetUtterance();
  }

  resetUtterance() {
    this.speechMs = 0;
    this.trailingMs = 0;
    this.stableMs = 0;
    this.lastText = "";
    this.heard = false;
    this.textSeen = false;
    this.maxProb = 0;
  }

  belowFloor(rms) {
    return this.floorReady && rms <= this.floor + this.floorMargin;
  }

  observe(vadVoiced, frameMs, rms, text, vadProb = vadVoiced ? 1 : 0, emberHot = false, emberText = "") {
    if (frameMs <= 0) return { decision: EndpointDecision.LISTEN };
    if (vadProb > this.maxProb) this.maxProb = vadProb;
    if (!this.floorReady) {
      if (!vadVoiced) this.floorSamples.push(rms);
      this.calibratedMs += frameMs;
      if (this.calibratedMs >= this.calibrateMs) {
        this.floor = median(this.floorSamples);
        this.floorReady = true;
      }
    }
    const spoken = text.trim();
    if (spoken !== "" && spoken !== this.lastText) {
      this.lastText = spoken;
      this.stableMs = 0;
      this.textSeen = true;
    } else if (this.textSeen && spoken !== "") {
      this.stableMs += frameMs;
    }
    const speech = vadVoiced && !this.belowFloor(rms);
    if (speech) {
      this.heard = true;
      this.speechMs += frameMs;
      this.trailingMs = 0;
      if (this.speechMs >= this.maxSpeechMs) return this.finish(EndpointReason.CAP, spoken, emberHot, emberText);
      if (this.textSeen && this.stableMs >= this.textStableMs) {
        return this.finish(EndpointReason.TEXT_STABLE, spoken, emberHot, emberText);
      }
      return { decision: EndpointDecision.LISTEN, speech: true };
    }
    if (!this.heard && !this.textSeen) return { decision: EndpointDecision.LISTEN };
    this.trailingMs += frameMs;
    if (this.textSeen && this.stableMs >= this.textStableMs) {
      return this.finish(EndpointReason.TEXT_STABLE, spoken, emberHot, emberText);
    }
    if (this.trailingMs >= this.silenceMs) return this.finish(EndpointReason.VAD_SILENCE, spoken, emberHot, emberText);
    return { decision: EndpointDecision.LISTEN };
  }

  finish(reason, text, emberHot, emberText) {
    const discard = screenHeardTurn(text, this.speechMs, this.maxProb, emberHot, emberText, this.barge);
    const event = discard == null && text.trim() !== "" && this.speechMs >= this.minSpeechMs
      ? {
          decision: EndpointDecision.SEND,
          reason,
          speech: true,
          vadSpeechMs: this.speechMs,
          maxProb: this.maxProb,
        }
      : {
          decision: EndpointDecision.DROP,
          discard: discard ?? DiscardReason.NO_VAD_SPEECH,
          vadSpeechMs: this.speechMs,
          maxProb: this.maxProb,
        };
    this.resetUtterance();
    return event;
  }
}
