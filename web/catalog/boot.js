import { createComposer } from "./src/composer.js";
import { mount, openMicrophone } from "./src/page.js";
import { aecFromSettings } from "../hearing/src/media.js";
import { createHearingClient } from "../hearing/src/hearing-client.js";
import { createKokoroEngine } from "../hearing/src/kokoro-engine.js";
import { KOKORO_VOICE_LIST, kokoroVoice } from "../hearing/src/kokoro-voices.js";
import { allCached } from "../hearing/src/model-cache.js";
import { KOKORO_WEB } from "../hearing/src/models.js";
import { createPcmPlayer } from "../hearing/src/pcm-player.js";
import { createSpeaker } from "../hearing/src/speaker.js";

const params = new URLSearchParams(location.search);
const scripted = params.get("scripted") === "1";
const hostMode = params.get("host") === "1";
// voice=stub keeps the recorder from the first web build: no download, nothing audible.
const voiceMode = params.get("voice") === "stub" ? "stub" : "kokoro";
const voiceBase = new URL(params.get("voiceBase") || "../voice-assets/", location.href).href;
const VOICE_KEY = "ember.voiceId";

const host = hostMode ? {
  chatState: "ready",
  calls: [],
  hearing(action) { this.calls.push(["hearing", action]); },
  send(text) { this.calls.push(["send", text]); },
  stop() { this.calls.push(["stop"]); },
} : null;

let aecAvailable = true;
let selfCheck = "pass";
let micStream = null;

if (!scripted && navigator.mediaDevices?.getUserMedia) {
  try {
    const stream = await openMicrophone();
    micStream = stream;
    const settings = stream.getAudioTracks()[0]?.getSettings?.() || {};
    aecAvailable = aecFromSettings(settings);
    // No loopback has been scored yet. A reported canceller is full duplex, unverified.
    // selfCheckFromLoopback marks fail when the phrase leaks or the VAD stays hot for 250 ms.
    selfCheck = "absent";
    window.__micSettings = settings;
    window.__constraintsUsed = true;
  } catch (error) {
    aecAvailable = false;
    selfCheck = "fail";
    window.__micError = String(error.message || error);
  }
}

let view = null;
const repaint = () => view?.paint();

function storedVoice() {
  try {
    return kokoroVoice(localStorage.getItem(VOICE_KEY) || "").id;
  } catch {
    return kokoroVoice("").id;
  }
}

let engine = null;
let player = null;
let speaker;
if (voiceMode === "stub") {
  speaker = {
    last: "",
    voiceId: kokoroVoice("").id,
    lastReport: null,
    speak(text, { onStart } = {}) {
      this.last = text;
      onStart?.();
      return new Promise(() => {});
    },
    stop() { this.last = ""; },
  };
} else {
  engine = createKokoroEngine({ baseUrl: voiceBase, backend: params.get("backend") || undefined, onProgress: repaint, onReady: repaint });
  player = createPcmPlayer({ sampleRate: KOKORO_WEB.sampleRate });
  speaker = createSpeaker({ engine, player, voiceId: storedVoice() });
}

function voiceStatus() {
  if (!engine) return "Voice: recorder only (voice=stub)";
  if (engine.status === "loading") {
    const { loaded, total } = engine.progress;
    const percent = total > 0 ? ` ${Math.floor((loaded / total) * 100)}%` : "";
    return `Getting Ember's voice ready…${percent}`;
  }
  if (engine.status === "ready") {
    return engine.report.backend === "webgpu" ? "Ember's voice is ready." : "Ember's voice is ready. This browser has no WebGPU, so replies start slower.";
  }
  if (engine.status === "error") return "Ember's voice is unavailable. Run web/hearing/scripts/fetch-web-voice.mjs, then reload.";
  return "Ember's voice loads when you first talk.";
}

// Hearing loads on the first talk click; until then the page only holds the microphone stream.
const hearing = micStream ? createHearingClient({ baseUrl: voiceBase, onProgress: repaint, onReady: repaint }) : null;

function hearingStatus() {
  if (!hearing) return scripted ? "Hearing: scripted frames (scripted=1)" : "";
  if (hearing.status === "loading") {
    const { loaded, total } = hearing.progress;
    const percent = total > 0 ? ` ${Math.floor((loaded / total) * 100)}%` : "";
    return `Getting Ember's hearing ready…${percent}`;
  }
  if (hearing.status === "ready") return "Ember can hear you.";
  if (hearing.status === "error") return "Ember's hearing is unavailable. Run web/hearing/scripts/fetch-web-voice.mjs, then reload.";
  return "Ember's hearing loads when you first talk.";
}

const composer = createComposer({ host, speaker, hearing, micStream, aecAvailable, selfCheck, onChange: repaint });
const voice = {
  voices: KOKORO_VOICE_LIST,
  selected: () => speaker.voiceId,
  select(id) {
    speaker.voiceId = kokoroVoice(id).id;
    try {
      localStorage.setItem(VOICE_KEY, speaker.voiceId);
    } catch {
      // A private window keeps the choice for this page only.
    }
  },
  status: voiceStatus,
  hearingStatus,
  say(text) {
    speaker.prepare?.();
    composer.reply(text);
  },
};
view = mount(document.querySelector("#app"), composer, voice);

if (engine) {
  // Files already in Cache Storage load without a download, so warm the voice up now.
  allCached(Object.values(KOKORO_WEB.files), { baseUrl: voiceBase }).then((cached) => {
    if (cached) engine.load().catch(repaint);
  });
}

window.__app = {
  feed(frame) {
    const event = composer.session.feed(frame);
    view.paint();
    return { event, phase: composer.session.phase, partial: composer.session.partial, line: lastLine(), calls: host?.calls || [] };
  },
  state() {
    return {
      phase: composer.session.phase,
      partial: composer.session.partial,
      notice: document.querySelector("#notice").textContent,
      chat: document.querySelector("#chat-status").textContent,
      duplex: document.querySelector("#duplex").textContent,
      voiceStatus: document.querySelector("#voice-status")?.textContent || "",
      hearingStatus: document.querySelector("#hearing-status")?.textContent || "",
      logs: composer.session.logs.slice(-20),
      line: lastLine(),
      calls: host?.calls || [],
    };
  },
  say(text) {
    voice.say(text);
    view.paint();
  },
  selectVoice(id) {
    voice.select(id);
    view.paint();
  },
  hearing: {
    report: () => hearing?.report ?? null,
  },
  voice: {
    load: () => engine?.load(),
    report: () => engine?.report ?? null,
    last: () => speaker.lastReport,
    stats: () => player?.stats() ?? null,
  },
};

function lastLine() {
  return voiceMode === "stub" ? speaker.last : speaker.lastReport?.text ?? "";
}
