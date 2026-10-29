/**
 * The hands-free loop: microphone chunks in, session frames and turns out.
 * A port of desktop's DesktopHearing.startSession with DesktopChat's playback
 * sync, in the same order per 100 ms chunk:
 *   1. a pending restart starts a new utterance, fed with the 300 ms pre-roll
 *      after a barge-in, or with just this chunk otherwise
 *   2. playback starting or stopping since the last chunk drops the utterance
 *      for the next chunk (noteEmberSpeaking / noteEmberSilent)
 *   3. the frame goes to the session (barge-in may ask for a pre-roll restart)
 *   4. an endpoint finishes the utterance; a send uses the finished text
 * Chunks are handled one at a time, like desktop's hearing thread. While the
 * session holds the microphone closed (half duplex), chunks are dropped and
 * only count down the resume guard.
 */

export const CHUNK_SAMPLES = 1600;

/** ready(): chunks are dropped until it is true (the models are still loading), instead of piling up. */
export function createListener({ session, hearing, onFrame = () => {}, onError = () => {}, ready = () => true }) {
  let running = false;
  let restart = true;
  let feedPreRoll = false;
  let playbackNoted = false;
  let wasOpen = true;
  let queue = [];
  let working = null;
  let capture = null;
  const audible = () => session.phase === "thinking" || session.phase === "speaking";

  async function handle(samples) {
    const chunkMs = Math.trunc((samples.length * 1000) / 16000);
    if (!session.talkOn) return;
    if (!session.micOpen) {
      wasOpen = false;
      session.feed({ frameMs: chunkMs, vadProb: 0, rms: 0, text: "" });
      return;
    }
    if (!wasOpen) {
      wasOpen = true;
      restart = true;
    }
    let mode = null;
    if (restart) {
      restart = false;
      mode = feedPreRoll ? "preroll" : "drop";
      feedPreRoll = false;
    }
    const frame = await hearing.chunk(samples, mode);
    if (!running) return;
    const nowAudible = audible();
    if (nowAudible !== playbackNoted) {
      playbackNoted = nowAudible;
      dropUtterance();
    }
    const event = session.feed({
      frameMs: frame.chunkMs,
      vadProb: frame.prob,
      voicedMs: frame.voicedMs,
      rms: frame.rms,
      text: frame.text,
      deferCommit: true,
    });
    onFrame(frame, event);
    if (!event || event.decision === "listen") return;
    const result = await hearing.finish();
    if (event.decision === "send" && running) session.commitTurn(result.text);
    restart = true;
  }

  function dropUtterance() {
    feedPreRoll = false;
    restart = true;
  }

  async function pump() {
    while (queue.length > 0 && running) {
      const samples = queue.shift();
      try {
        await handle(samples);
      } catch (error) {
        onError(error);
      }
    }
    working = null;
  }

  const listener = {
    get running() {
      return running;
    },
    /** capture: { start(onChunk), stop() } (createMicCapture), or null to push chunks by hand. */
    async start(source = null) {
      if (running) return;
      running = true;
      restart = true;
      feedPreRoll = false;
      playbackNoted = audible();
      wasOpen = true;
      queue = [];
      capture = source;
      await capture?.start((samples) => listener.push(samples));
    },
    async stop() {
      running = false;
      queue = [];
      const closing = capture;
      capture = null;
      await closing?.stop();
    },
    push(samples) {
      if (!running || !ready()) return;
      queue.push(samples);
      working ??= pump();
    },
    /** Resolves when every pushed chunk has been handled. */
    idle() {
      return working ?? Promise.resolve();
    },
    /** Barge-in keeps the last 300 ms, as desktop's restartUtterance(). */
    restartWithPreRoll() {
      feedPreRoll = true;
      restart = true;
    },
  };
  return listener;
}

/** 16 kHz microphone chunks through an AudioWorklet. Start it from a click so the AudioContext may run. */
export function createMicCapture(stream, options = {}) {
  const chunkSamples = options.chunkSamples ?? CHUNK_SAMPLES;
  const workletUrl = options.workletUrl ?? new URL("./mic-capture-worklet.js", import.meta.url);
  let context = null;
  let nodes = [];
  return {
    async start(onChunk) {
      context = new AudioContext({ sampleRate: 16000, latencyHint: "interactive" });
      await context.audioWorklet.addModule(workletUrl);
      const source = context.createMediaStreamSource(stream);
      const tap = new AudioWorkletNode(context, "ember-mic-capture", { processorOptions: { chunkSamples }, numberOfOutputs: 1 });
      const mute = context.createGain();
      mute.gain.value = 0;
      tap.port.onmessage = (event) => onChunk(event.data);
      source.connect(tap).connect(mute).connect(context.destination);
      nodes = [source, tap, mute];
      if (context.state === "suspended") await context.resume();
    },
    async stop() {
      for (const node of nodes) node.disconnect();
      nodes = [];
      await context?.close();
      context = null;
    },
  };
}
