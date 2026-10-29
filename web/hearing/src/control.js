/** Presentational names from the shared hearing states. No microphone and no product copy. */

export const STATE_NAMES = {
  off: "Mic off",
  requesting: "Allow the microphone",
  listening: "Listening",
  hearing: "Hearing you",
  thinking: "Ember is thinking",
  speaking: "Ember is speaking",
  muted: "Mic muted",
  unavailable: "Voice input unavailable",
};

export function accessibleName(state) {
  return STATE_NAMES[state] || STATE_NAMES.unavailable;
}
