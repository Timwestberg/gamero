/**
 * PCM playback through an AudioWorklet, so the far end goes through the
 * page's audio graph (where the browser echo canceller can hear it) and a
 * flush is silent at the next 128-frame render quantum.
 *
 * Audio is pushed in chunks of CHUNK_MS. "Barge-in within one chunk" means
 * the frames heard after stop() are fewer than one chunk; pcm-player keeps
 * that number in stats().flushes for the tests.
 */

export const CHUNK_MS = 100;

export function createPcmPlayer(options = {}) {
  const sampleRate = options.sampleRate ?? 24000;
  const chunkSamples = Math.floor((sampleRate * (options.chunkMs ?? CHUNK_MS)) / 1000);
  const workletUrl = options.workletUrl ?? new URL("./pcm-player-worklet.js", import.meta.url);
  let context = options.context ?? null;
  let node = null;
  let starting = null;
  let seq = 0;
  let flushToken = 0;
  let markWaiters = new Map();
  let drainWaiters = [];
  let drainedSeq = 0;
  const flushes = [];
  const reachedMarks = new Set();

  function handle(event) {
    const message = event.data;
    if (message.type === "mark") {
      reachedMarks.add(message.mark);
      const waiter = markWaiters.get(message.mark);
      if (waiter) {
        markWaiters.delete(message.mark);
        reachedMarks.delete(message.mark);
        waiter(true);
      }
    } else if (message.type === "drained") {
      drainedSeq = message.seq;
      if (message.seq !== seq) return;
      const waiters = drainWaiters;
      drainWaiters = [];
      for (const waiter of waiters) waiter(true);
    } else if (message.type === "flushed") {
      const entry = flushes.find((flush) => flush.token === message.token);
      if (entry) {
        entry.flushedFrame = message.frame;
        entry.dropped = message.dropped;
        entry.heardAfterStop = Math.max(0, message.frame - entry.requestedFrame);
      }
    }
  }

  const player = {
    sampleRate,
    chunkSamples,
    async start() {
      starting ??= (async () => {
        context ??= new AudioContext({ sampleRate, latencyHint: "interactive" });
        await context.audioWorklet.addModule(workletUrl);
        node = new AudioWorkletNode(context, "ember-pcm-player", {
          numberOfInputs: 0,
          numberOfOutputs: 1,
          outputChannelCount: [1],
        });
        node.port.onmessage = handle;
        node.connect(context.destination);
      })();
      await starting;
      if (context.state === "suspended") await context.resume();
    },
    /** Queues samples. A mark (any unique value) resolves reached(mark) when its first sample plays. */
    enqueue(samples, { mark } = {}) {
      if (!node) throw new Error("start() the player first");
      for (let offset = 0; offset < samples.length; offset += chunkSamples) {
        const chunk = samples.slice(offset, offset + chunkSamples);
        seq += 1;
        const message = { type: "push", samples: chunk, seq };
        if (offset === 0 && mark !== undefined) message.mark = mark;
        node.port.postMessage(message, [chunk.buffer]);
      }
    },
    /** true when the mark plays, false if a flush comes first. */
    reached(mark) {
      if (reachedMarks.delete(mark)) return Promise.resolve(true);
      return new Promise((resolve) => markWaiters.set(mark, resolve));
    },
    /** true when everything queued so far has played, false on flush. */
    drained() {
      if (drainedSeq === seq) return Promise.resolve(true);
      return new Promise((resolve) => drainWaiters.push(resolve));
    },
    /** Silences playback at the next render quantum and drops the queue. */
    flush() {
      if (!node) return;
      flushToken += 1;
      flushes.push({ token: flushToken, requestedFrame: Math.round(context.currentTime * sampleRate) });
      node.port.postMessage({ type: "flush", token: flushToken });
      reachedMarks.clear();
      const marks = markWaiters;
      markWaiters = new Map();
      for (const waiter of marks.values()) waiter(false);
      const drains = drainWaiters;
      drainWaiters = [];
      for (const waiter of drains) waiter(false);
    },
    /**
     * heardAfterStop counts frames the worklet rendered after stop() was called. The device's own
     * output buffer (baseLatency + outputLatency, in seconds) plays out after that; no page can flush it.
     */
    stats() {
      return {
        chunkSamples,
        sampleRate,
        baseLatency: context?.baseLatency ?? null,
        outputLatency: context?.outputLatency ?? null,
        flushes: flushes.map((flush) => ({ ...flush })),
      };
    },
    async close() {
      player.flush();
      await context?.close();
    },
  };
  return player;
}
