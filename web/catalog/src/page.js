import { accessibleName } from "../../hearing/src/control.js";
import { WEB_MIC_CONSTRAINTS } from "../../hearing/src/media.js";
import { createComposer, MIC_SETTINGS_NOTICE, PERMISSION_COPY } from "./composer.js";

export function mount(root, composer) {
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

  function paint() {
    const session = composer.session;
    talk.textContent = accessibleName(session.phase === "off" ? "off" : session.phase);
    talk.setAttribute("aria-pressed", session.talkOn ? "true" : "false");
    partial.textContent = session.partial;
    if (!session.explained && session.phase === "off") notice.textContent = PERMISSION_COPY;
    else notice.textContent = session.notice || "";
    chat.textContent = composer.chatNotice || "";
    duplex.textContent = session.duplex.mode === "full" ? "Full duplex" : `Half duplex · ${session.duplex.reason}`;
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
