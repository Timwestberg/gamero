/**
 * AudioWorklet side of pcm-player.js. Plays queued mono chunks. A flush
 * empties the queue at the next render quantum (128 frames), so barge-in is
 * silent well inside one chunk.
 *
 * In:  { type: "push", samples, seq, mark? } | { type: "flush", token }
 * Out: { type: "mark", mark, frame } when a marked chunk starts
 *      { type: "drained", seq, frame } when the queue runs dry after audio
 *      { type: "flushed", token, frame, played, dropped }
 */
class EmberPcmPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.queue = [];
    this.played = 0;
    this.lastSeq = 0;
    this.sounding = false;
    this.port.onmessage = (event) => {
      const message = event.data;
      if (message.type === "push") {
        this.queue.push({ samples: message.samples, offset: 0, seq: message.seq, mark: message.mark });
      } else if (message.type === "flush") {
        let dropped = 0;
        for (const chunk of this.queue) dropped += chunk.samples.length - chunk.offset;
        this.queue = [];
        this.sounding = false;
        this.port.postMessage({ type: "flushed", token: message.token, frame: currentFrame, played: this.played, dropped });
      }
    };
  }

  process(_inputs, outputs) {
    const channels = outputs[0];
    const out = channels[0];
    let written = 0;
    while (written < out.length && this.queue.length > 0) {
      const chunk = this.queue[0];
      if (chunk.offset === 0 && chunk.mark !== undefined) {
        this.port.postMessage({ type: "mark", mark: chunk.mark, frame: currentFrame + written });
      }
      const count = Math.min(out.length - written, chunk.samples.length - chunk.offset);
      out.set(chunk.samples.subarray(chunk.offset, chunk.offset + count), written);
      chunk.offset += count;
      written += count;
      this.played += count;
      if (chunk.offset >= chunk.samples.length) {
        this.lastSeq = chunk.seq;
        this.queue.shift();
      }
    }
    if (written < out.length) out.fill(0, written);
    for (let channel = 1; channel < channels.length; channel += 1) channels[channel].set(out);
    if (written > 0) this.sounding = true;
    if (this.sounding && this.queue.length === 0) {
      this.sounding = false;
      this.port.postMessage({ type: "drained", seq: this.lastSeq, frame: currentFrame + written });
    }
    return true;
  }
}

registerProcessor("ember-pcm-player", EmberPcmPlayer);
