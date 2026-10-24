import Foundation

/// Times the first callback inside ember_voice_sherpa_speak. That callback is
/// sherpa-onnx's first audio chunk, which arrives after the language model
/// finishes the piece.
public final class SherpaSynthesizer: BenchSynthesizer {
    private let engine: OpaquePointer

    init(modelDir: String, voiceWav: String, threads: Int32) {
        guard let created = ember_voice_sherpa_create(modelDir, voiceWav, threads) else {
            fatalError("sherpa-onnx did not load. Check the model dir, the voice wav, and the xcframework.")
        }
        engine = OpaquePointer(created)
    }

    deinit {
        ember_voice_sherpa_destroy(UnsafeMutableRawPointer(engine))
    }

    public func speak(text: String, maxFrames: Int, seed: Int, steps: Int, temperature: Float) -> PieceTake {
        let code = text.withCString { cText in
            ember_voice_sherpa_speak(
                UnsafeMutableRawPointer(engine),
                cText,
                Int32(maxFrames),
                Int32(steps),
                temperature,
                Int32(seed),
                2,
                nil,
                nil
            )
        }
        if code == 0 {
            fatalError("no audio for \(text)")
        }
        let raw = UnsafeMutableRawPointer(engine)
        let rate = Int(ember_voice_sherpa_sample_rate(raw))
        return PieceTake(
            ttfaMs: ember_voice_sherpa_last_ttfa_ms(raw),
            samples: Int(ember_voice_sherpa_last_samples(raw)),
            sampleRate: rate > 0 ? rate : 24000
        )
    }
}
