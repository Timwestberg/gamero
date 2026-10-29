import { accessibleName } from "../../hearing/src/control.js";
import { WEB_MIC_CONSTRAINTS } from "../../hearing/src/media.js";
import { createComposer, MIC_SETTINGS_NOTICE, PERMISSION_COPY } from "./composer.js";

/**
 * voice (optional): { voices: [{ id, name }], selected(), select(id), status(), say(text) }.
 * It adds the voice picker, the "getting Ember's voice ready" line, and a box
 * that speaks a reply as if the host had sent it.
 */
export function mount(root, composer, voice = null) {
  root.replaceChildren();
  const talk = document.createElement("button");
  talk.type = "button";
  talk.id = "talk";
  const partial = document.createElement("p");
  partial.id = "partial";
  const notice = document.createElement("p");
  notice.id = "notice";
  const chat = document.createElement("p");
  chat.id = "chat-status";
  const duplex = document.createElement("p");
  duplex.id = "duplex";
  root.append(talk, partial, notice, chat, duplex);

  let picker = null;
  let voiceStatus = null;
  let hearingStatus = null;
  if (voice) {
    const label = document.createElement("label");
    label.textContent = "Ember's voice ";
    picker = document.createElement("select");
    picker.id = "voice";
    for (const option of voice.voices) {
      const item = document.createElement("option");
      item.value = option.id;
      item.textContent = option.name;
      picker.append(item);
    }
    label.append(picker);
    voiceStatus = document.createElement("p");
    voiceStatus.id = "voice-status";
    voiceStatus.setAttribute("role", "status");
    hearingStatus = document.createElement("p");
    hearingStatus.id = "hearing-status";
    hearingStatus.setAttribute("role", "status");
    const form = document.createElement("form");
    form.id = "say-form";
    const text = document.createElement("input");
    text.id = "say-text";
    text.type = "text";
    text.value = "Hi, I'm Ember. Sonnet 5.5 answered in 160 ms. What should we build next?";
    text.setAttribute("aria-label", "Reply for Ember to speak");
    const say = document.createElement("button");
    say.id = "say";
    say.type = "submit";
    say.textContent = "Say it";
    form.append(text, say);
    root.append(label, voiceStatus, hearingStatus, form);
    picker.addEventListener("change", () => {
      voice.select(picker.value);
      paint();
    });
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      voice.say(text.value);
      paint();
    });
  }

  function paint() {
    const session = composer.session;
    talk.textContent = accessibleName(session.phase === "off" ? "off" : session.phase);
    talk.setAttribute("aria-pressed", session.talkOn ? "true" : "false");
    partial.textContent = session.partial;
    if (!session.explained && session.phase === "off") notice.textContent = PERMISSION_COPY;
    else notice.textContent = session.notice || "";
    chat.textContent = composer.chatNotice || "";
    duplex.textContent = session.duplex.mode === "full" ? "Full duplex" : `Half duplex · ${session.duplex.reason}`;
    if (voice) {
      picker.value = voice.selected();
      voiceStatus.textContent = voice.status();
      hearingStatus.textContent = voice.hearingStatus?.() ?? "";
    }
  }

  talk.addEventListener("click", () => {
    composer.session.click();
    paint();
  });
  root.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    if (composer.session.escape()) event.preventDefault();
    paint();
  });
  paint();
  return { paint };
}

export async function openMicrophone(devices = navigator.mediaDevices) {
  try {
    return await devices.getUserMedia(WEB_MIC_CONSTRAINTS);
  } catch (error) {
    const denied = String(error?.name || error).toLowerCase().includes("notallowed") || String(error).toLowerCase().includes("denied");
    throw new Error(denied ? MIC_SETTINGS_NOTICE : "The microphone did not start.");
  }
}
