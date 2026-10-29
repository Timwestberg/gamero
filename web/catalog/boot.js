import { createComposer } from "./src/composer.js";
import { mount, openMicrophone } from "./src/page.js";
import { aecFromSettings } from "../../hearing/src/media.js";

const scripted = new URLSearchParams(location.search).get("scripted") === "1";
const hostMode = new URLSearchParams(location.search).get("host") === "1";

const player = {
  last: "",
  stop() { this.last = ""; },
  speak(line) { this.last = line; },
};

const host = hostMode ? {
  chatState: "ready",
  calls: [],
  hearing(action) { this.calls.push(["hearing", action]); },
  send(text) { this.calls.push(["send", text]); },
  stop() { this.calls.push(["stop"]); },
} : null;

let aecAvailable = true;
let selfCheck = "pass";

if (!scripted && navigator.mediaDevices?.getUserMedia) {
  try {
    const stream = await openMicrophone();
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

const composer = createComposer({ host, player, aecAvailable, selfCheck });
const view = mount(document.querySelector("#app"), composer);
window.__app = {
  feed(frame) {
    const event = composer.session.feed(frame);
    view.paint();
    return { event, phase: composer.session.phase, partial: composer.session.partial, line: player.last, calls: host?.calls || [] };
  },
  state() {
    return {
      phase: composer.session.phase,
      partial: composer.session.partial,
      notice: document.querySelector("#notice").textContent,
      chat: document.querySelector("#chat-status").textContent,
      duplex: document.querySelector("#duplex").textContent,
      line: player.last,
      calls: host?.calls || [],
    };
  },
};
