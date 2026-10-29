/** What the page asks the browser for. Echo cancellation has to be on before full duplex. */
export const WEB_MIC_CONSTRAINTS = {
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: false,
    channelCount: 1,
    sampleRate: 16000,
  },
  video: false,
};

export function aecFromSettings(settings) {
  return settings?.echoCancellation === true;
}

/**
 * Played line vs what the mic still contains. A hot VAD run or a leaked word is a failed check.
 * The session then stays half duplex and does not leave the mic open while Ember speaks.
 */
export function selfCheckFromLoopback({ vadHotMs, transcript, phrase, sustainMs }) {
  const leaked = leaksPhrase(transcript, phrase);
  if (vadHotMs >= sustainMs || leaked) return "fail";
  return "pass";
}

export function leaksPhrase(transcript, phrase) {
  const heard = (transcript || "").toLowerCase();
  return phrase
    .split(/\s+/)
    .map((word) => word.replace(/[.,]/g, "").toLowerCase())
    .filter((word) => word.length >= 4)
    .some((word) => word && heard.includes(word));
}

export const ECHO_SELF_TEST_PHRASE = "Ember checking the microphone.";
