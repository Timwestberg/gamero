/**
 * Pinned hearing and Kokoro files. Bytes are fetched with GET, checked, and cached
 * on this origin. They are never posted to the Hovel service.
 */

export const MODEL_PINS = {
  sileroVadSha256: "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396",
  zipformerArchiveSha256: "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
  zipformerEncoderSha256: "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87",
  zipformerDecoderSha256: "85914f840b71110487ece7a30ebd0bd4b8fb5c2623215bba3aa5ab4c138fc591",
  zipformerJoinerSha256: "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d",
  zipformerTokensSha256: "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
  modelOnnxSha256: "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
  tokensSha256: "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425",
  lexiconSha256: "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
  upstreamVoicesSha256: "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
  derivedVoicesSha256: "35cce6c473585fd74244f31c2623d6f2f835283918dad6fa2e2a79d60639b26f",
};

export const MODEL_URLS = {
  sileroVad: "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx",
  zipformerArchive: "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
  kokoroArchive: "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
};

const REFUSED = [/kroko/i, /cc-by-nc/i, /non-commercial/i, /expresso/i, /test_wavs/i];

export function refuseNonCommercial(label) {
  if (REFUSED.some((pattern) => pattern.test(label))) {
    throw new Error(`refused non-commercial voice file: ${label}`);
  }
}

export function assertNotHovel(url, method = "GET") {
  if (method !== "GET") throw new Error("model bytes are only fetched with GET");
  const host = new URL(url).host;
  if (host === "hovel.test" || host.endsWith(".hovel") || host.includes("hovel-service")) {
    throw new Error("model bytes are not sent to the Hovel service");
  }
}

export async function sha256Hex(bytes) {
  const buffer = bytes instanceof ArrayBuffer ? bytes : bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function fetchChecked(url, expectedSha256, fetchImpl = globalThis.fetch, licenseText = "") {
  refuseNonCommercial(`${url}\n${licenseText}`);
  assertNotHovel(url, "GET");
  const response = await fetchImpl(url, { method: "GET" });
  if (!response.ok) throw new Error(`model fetch failed: ${response.status}`);
  const bytes = new Uint8Array(await response.arrayBuffer());
  const got = await sha256Hex(bytes);
  if (got !== expectedSha256) throw new Error(`hash mismatch for ${url}`);
  return bytes;
}
