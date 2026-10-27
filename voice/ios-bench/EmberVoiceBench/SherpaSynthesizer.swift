import Foundation

/// Times the first callback inside ember_voice_sherpa_speak. That callback is
/// sherpa-onnx's first audio chunk, which arrives after the language model
/// finishes the piece.
public final class SherpaSynthesizer: BenchSynthesizer {
    private let engine: OpaquePointer
    private let chunkSize: Int32

    init(modelDir: String, voiceWav: String, threads: Int32, chunkSize: Int32) {
        self.chunkSize = chunkSize
        guard let created = ember_voice_sherpa_create(modelDir, voiceWav, threads) else {
            fatalError("sherpa-onnx did not load. Check the model dir, the voice wav, and the xcframework.")
        }
        engine = OpaquePointer(created)
    }

    deinit {
        ember_voice_sherpa_destroy(UnsafeMutableRawPointer(engine))
    }

    public func speak(text: String, maxFrames: Int, seed: Int, steps: Int, temperature: Float) -> PieceTake {
        let started = Date()
        let code = text.withCString { cText in
            ember_voice_sherpa_speak(
                UnsafeMutableRawPointer(engine),
                cText,
                Int32(maxFrames),
                Int32(steps),
                temperature,
                Int32(seed),
                chunkSize,
                nil,
                nil
            )
        }
        let synthMs = Date().timeIntervalSince(started) * 1000.0
        if code == 0 {
            fatalError("no audio for \(text)")
        }
        let raw = UnsafeMutableRawPointer(engine)
        let rate = Int(ember_voice_sherpa_sample_rate(raw))
        return PieceTake(
            ttfaMs: ember_voice_sherpa_last_ttfa_ms(raw),
            synthMs: synthMs,
            samples: Int(ember_voice_sherpa_last_samples(raw)),
            sampleRate: rate > 0 ? rate : 24000
        )
    }
}
