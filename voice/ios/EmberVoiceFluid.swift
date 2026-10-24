import Foundation

// Optional iOS backend. The default remains sherpa-onnx.
// Link the FluidAudio package and this file takes the real path. Without that
// import the symbols still exist and return NULL, and the Kotlin engine says
// FluidAudio is not linked.
//
// Expected FluidAudio surface, from the PocketTTS manager:
//   PocketTtsManager()
//   synthesizeStreaming(text) -> AsyncThrowingStream of frames
//   each frame is 24 kHz mono, 1920 samples (80 ms)

@_cdecl("ember_voice_fluid_create")
public func emberVoiceFluidCreate(
    _ voiceWav: UnsafePointer<CChar>?,
    _ temperature: Float,
    _ seed: Int32
) -> UnsafeMutableRawPointer? {
#if canImport(FluidAudio)
    guard let voiceWav else { return nil }
    let box = FluidBox(voiceWav: String(cString: voiceWav), temperature: temperature, seed: seed)
    return Unmanaged.passRetained(box).toOpaque()
#else
    _ = voiceWav
    _ = temperature
    _ = seed
    return nil
#endif
}

@_cdecl("ember_voice_fluid_destroy")
public func emberVoiceFluidDestroy(_ engine: UnsafeMutableRawPointer?) {
#if canImport(FluidAudio)
    guard let engine else { return }
    Unmanaged<FluidBox>.fromOpaque(engine).release()
#else
    _ = engine
#endif
}

@_cdecl("ember_voice_fluid_sample_rate")
public func emberVoiceFluidSampleRate(_ engine: UnsafeMutableRawPointer?) -> Int32 {
    _ = engine
    return 24000
}

@_cdecl("ember_voice_fluid_last_ttfa_ms")
public func emberVoiceFluidLastTtfaMs(_ engine: UnsafeMutableRawPointer?) -> Double {
#if canImport(FluidAudio)
    guard let engine else { return 0 }
    return Unmanaged<FluidBox>.fromOpaque(engine).takeUnretainedValue().lastTtfaMs
#else
    _ = engine
    return 0
#endif
}

@_cdecl("ember_voice_fluid_last_samples")
public func emberVoiceFluidLastSamples(_ engine: UnsafeMutableRawPointer?) -> Int32 {
#if canImport(FluidAudio)
    guard let engine else { return 0 }
    return Unmanaged<FluidBox>.fromOpaque(engine).takeUnretainedValue().lastSamples
#else
    _ = engine
    return 0
#endif
}

@_cdecl("ember_voice_fluid_speak")
public func emberVoiceFluidSpeak(
    _ engine: UnsafeMutableRawPointer?,
    _ text: UnsafePointer<CChar>?,
    _ maxFrames: Int32,
    _ callback: EmberVoiceSamplesFn?,
    _ arg: UnsafeMutableRawPointer?
) -> Int32 {
#if canImport(FluidAudio)
    guard let engine, let text, let callback else { return 0 }
    let box = Unmanaged<FluidBox>.fromOpaque(engine).takeUnretainedValue()
    return box.speak(text: String(cString: text), maxFrames: maxFrames, callback: callback, arg: arg)
#else
    _ = engine
    _ = text
    _ = maxFrames
    _ = callback
    _ = arg
    return 0
#endif
}

public typealias EmberVoiceSamplesFn = @convention(c) (
    UnsafePointer<Float>?,
    Int32,
    UnsafeMutableRawPointer?
) -> Int32

#if canImport(FluidAudio)
import FluidAudio

final class FluidBox {
    let voiceWav: String
    let temperature: Float
    let seed: Int32
    var lastTtfaMs: Double = 0
    var lastSamples: Int32 = 0
    init(voiceWav: String, temperature: Float, seed: Int32) {
        self.voiceWav = voiceWav
        self.temperature = temperature
        self.seed = seed
    }

    func speak(text: String, maxFrames: Int32, callback: EmberVoiceSamplesFn?, arg: UnsafeMutableRawPointer?) -> Int32 {
        // maxFrames is the same ceiling the sherpa path uses. FluidAudio's
        // streaming call does not take a frame cap today; the piece list is
        // already cut to the sizes in pieces.json.
        _ = maxFrames
        _ = voiceWav
        _ = temperature
        _ = seed
        let group = DispatchGroup()
        group.enter()
        var produced = 0
        var failed = false
        let started = Date()
        var first: Date?
        Task {
            do {
                let manager = try PocketTtsManager()
                let stream = manager.synthesizeStreaming(text)
                for try await frame in stream {
                    let samples = frame.samples
                    if samples.isEmpty { continue }
                    if first == nil { first = Date() }
                    produced += samples.count
                    let keep = samples.withUnsafeBufferPointer { buffer -> Int32 in
                        guard let base = buffer.baseAddress, let callback else { return 1 }
                        return callback(base, Int32(buffer.count), arg)
                    }
                    if keep == 0 { break }
                }
            } catch {
                failed = true
            }
            group.leave()
        }
        group.wait()
        lastSamples = Int32(produced)
        lastTtfaMs = (first ?? Date()).timeIntervalSince(started) * 1000.0
        return (!failed && produced > 0) ? 1 : 0
    }
}
#endif
