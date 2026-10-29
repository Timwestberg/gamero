/**
 * AudioWorklet side of listener.js. The AudioContext runs at 16 kHz, so the
 * browser resamples the microphone. This only cuts the first channel into
 * chunks of `chunkSamples` (desktop reads 1600, 100 ms) and posts them.
 */
class EmberMicCapture extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.size = options.processorOptions?.chunkSamples ?? 1600;
    this.buffer = new Float32Array(this.size);
    this.fill = 0;
  }

  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) return true;
    let offset = 0;
    while (offset < channel.length) {
      const count = Math.min(this.size - this.fill, channel.length - offset);
      this.buffer.set(channel.subarray(offset, offset + count), this.fill);
      this.fill += count;
      offset += count;
      if (this.fill === this.size) {
        this.port.postMessage(this.buffer, [this.buffer.buffer]);
        this.buffer = new Float32Array(this.size);
        this.fill = 0;
      }
    }
    return true;
  }
}

registerProcessor("ember-mic-capture", EmberMicCapture);
