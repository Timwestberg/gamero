#!/usr/bin/env node
/**
 * Writes Ember's browser voice into web/voice-assets/ (not committed):
 *
 *   kokoro-v1_0-fp16w.onnx                 sherpa Kokoro v1.0 with float16-stored weights
 *   voices-ember.bin                       Sam, Jamie, Riley, Harper style tables
 *   tokens.txt, lexicon-us-en.txt          upstream, unchanged
 *   ort-wasm-simd-threaded.asyncify.wasm   onnxruntime-web 1.30.0 runtime
 *   hearing/                               Silero VAD v5 and the streaming Zipformer, one file at a time
 *                                          (never the sherpa archive, which carries test_wavs)
 *
 * Every upstream file and every output is checked against the pins in
 * src/models.js and src/kokoro-voices.js. Upstream bytes are reused from
 * --from <dir> or desktop's spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0
 * when their hashes match, and downloaded otherwise (GET only).
 *
 * The model step runs voice/kokoro/fp16-weights.py (python3 with onnx and
 * numpy; set KOKORO_PYTHON to a venv's python).
 *
 * usage: node web/hearing/scripts/fetch-web-voice.mjs [--from dir] [--out dir]
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, existsSync } from "node:fs";
import { copyFile, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { applyBlends, bytesFromFloats, emberStyles, floatsFromBytes, KOKORO_BLENDS } from "../src/kokoro-voices.js";
import { HEARING_WEB, KOKORO_WEB, MODEL_PINS, assertNotHovel, refuseNonCommercial } from "../src/models.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const hearing = path.resolve(here, "..");
const repo = path.resolve(hearing, "../..");

const args = process.argv.slice(2);
const option = (name) => {
  const at = args.indexOf(name);
  return at >= 0 ? args[at + 1] : null;
};
const out = path.resolve(option("--out") ?? path.join(repo, "web/voice-assets"));
const upstreamDir = path.join(out, ".upstream");
const sources = [option("--from"), path.join(repo, "spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0")].filter(Boolean);

const UPSTREAM = {
  model: { name: "model.onnx", sha256: MODEL_PINS.modelOnnxSha256, url: KOKORO_WEB.upstream.modelFp32 },
  voices: { name: "voices.bin", sha256: MODEL_PINS.upstreamVoicesSha256, url: KOKORO_WEB.upstream.voices },
  tokens: { name: "tokens.txt", sha256: MODEL_PINS.tokensSha256, url: KOKORO_WEB.upstream.tokens },
  lexicon: { name: "lexicon-us-en.txt", sha256: MODEL_PINS.lexiconSha256, url: KOKORO_WEB.upstream.lexicon },
};

async function sha256File(file) {
  const hash = createHash("sha256");
  await pipeline(createReadStream(file), hash);
  return hash.digest("hex");
}

async function hashOk(file, sha256) {
  return existsSync(file) && (await sha256File(file)) === sha256;
}

async function upstream(key) {
  const pin = UPSTREAM[key];
  const target = path.join(upstreamDir, pin.name);
  if (await hashOk(target, pin.sha256)) return target;
  for (const dir of sources) {
    const candidate = path.join(dir, pin.name);
    if (await hashOk(candidate, pin.sha256)) {
      await copyFile(candidate, target);
      console.log(`ok   ${pin.name} (from ${dir})`);
      return target;
    }
  }
  refuseNonCommercial(pin.url);
  assertNotHovel(pin.url, "GET");
  console.log(`get  ${pin.url}`);
  const response = await fetch(pin.url, { method: "GET", redirect: "follow" });
  if (!response.ok) throw new Error(`${pin.name}: HTTP ${response.status}`);
  const partial = `${target}.partial`;
  await pipeline(Readable.fromWeb(response.body), createWriteStream(partial));
  if (!(await hashOk(partial, pin.sha256))) {
    await rm(partial, { force: true });
    throw new Error(`hash mismatch for ${pin.name}`);
  }
  await rename(partial, target);
  return target;
}

async function download(url, target, sha256) {
  refuseNonCommercial(url);
  assertNotHovel(url, "GET");
  console.log(`get  ${url}`);
  const response = await fetch(url, { method: "GET", redirect: "follow" });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  const partial = `${target}.partial`;
  await pipeline(Readable.fromWeb(response.body), createWriteStream(partial));
  if (!(await hashOk(partial, sha256))) {
    await rm(partial, { force: true });
    throw new Error(`hash mismatch for ${url}`);
  }
  await rename(partial, target);
}

/** Hearing files are used as published: no transform, only the pinned hash. */
async function hearingFile(key) {
  const file = HEARING_WEB.files[key];
  const target = path.join(out, file.name);
  await mkdir(path.dirname(target), { recursive: true });
  if (!(await hashOk(target, file.sha256))) {
    const base = path.basename(file.name);
    const url = key === "vad" ? HEARING_WEB.upstream.vad : `${HEARING_WEB.upstream.zipformer}${base}`;
    await download(url, target, file.sha256);
  }
  await place(file, target);
}

async function place(file, bytesOrPath) {
  const target = path.join(out, file.name);
  if (typeof bytesOrPath !== "string") await writeFile(target, bytesOrPath);
  else if (path.resolve(bytesOrPath) !== target) await copyFile(bytesOrPath, target);
  const size = (await stat(target)).size;
  const sha = await sha256File(target);
  if (size !== file.bytes || sha !== file.sha256) {
    throw new Error(`${file.name}: got ${size} bytes ${sha}, pinned ${file.bytes} bytes ${file.sha256}`);
  }
  console.log(`ok   ${file.name} ${size.toLocaleString("en-US")} bytes`);
}

async function main() {
  await mkdir(upstreamDir, { recursive: true });
  const files = KOKORO_WEB.files;

  await place(files.tokens, await upstream("tokens"));
  await place(files.lexicon, await upstream("lexicon"));

  const table = floatsFromBytes(new Uint8Array(await readFile(await upstream("voices"))));
  const derived = bytesFromFloats(applyBlends(table));
  const derivedSha = createHash("sha256").update(derived).digest("hex");
  if (derivedSha !== KOKORO_BLENDS.derivedVoicesSha256) {
    throw new Error(`derived voices ${derivedSha} != desktop ${KOKORO_BLENDS.derivedVoicesSha256}`);
  }
  console.log("ok   blends match desktop voices-blends.bin");
  await place(files.styles, bytesFromFloats(emberStyles(floatsFromBytes(derived))));

  const modelTarget = path.join(out, files.model.name);
  if (!(await hashOk(modelTarget, files.model.sha256))) {
    const python = process.env.KOKORO_PYTHON || "python3";
    execFileSync(python, ["-I", path.join(repo, "voice/kokoro/fp16-weights.py"), "--input", await upstream("model"), "--output", modelTarget], { stdio: "inherit" });
  }
  await place(files.model, modelTarget);

  const ortWasm = path.join(hearing, "node_modules/onnxruntime-web/dist", files.ortWasm.name);
  if (!existsSync(ortWasm)) throw new Error("run npm install in web/hearing first (onnxruntime-web 1.30.0)");
  await place(files.ortWasm, ortWasm);

  for (const key of Object.keys(HEARING_WEB.files)) await hearingFile(key);

  const total = Object.values(files).reduce((sum, file) => sum + file.bytes, 0);
  const hearingTotal = Object.values(HEARING_WEB.files).reduce((sum, file) => sum + file.bytes, 0);
  console.log(`\nEmber's browser voice is ready in ${path.relative(process.cwd(), out) || out}: ${total.toLocaleString("en-US")} bytes on first load.`);
  console.log(`Her hearing adds ${hearingTotal.toLocaleString("en-US")} bytes the first time the microphone is used.`);
  console.log("Remove the .upstream folder to reclaim the 360 MB of source files.");
}

main().catch((error) => {
  console.error(String(error?.message || error));
  process.exit(1);
});
