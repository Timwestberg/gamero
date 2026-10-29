import Foundation

// Optional iOS backend. The default remains sherpa-onnx.
// Link the FluidAudio package and this file takes the real path. Without that
// import the symbols still exist and return NULL, and the Kotlin engine says
// FluidAudio is not linked.
//
// Expected FluidAudio surface, from the PocketTTS manager:
//   PocketTtsManager() is created once per conversation, then initialize()
//   and cloneVoice(from:) run once. Later speaks only makeSession on that clone.
//   Each frame is 24 kHz mono, 1920 samples (80 ms). The bundled named voice is not used.

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
    _ seed: Int32,
    _ callback: EmberVoiceSamplesFn?,
    _ arg: UnsafeMutableRawPointer?
) -> Int32 {
#if canImport(FluidAudio)
    guard let engine, let text else { return 0 }
    let box = Unmanaged<FluidBox>.fromOpaque(engine).takeUnretainedValue()
    return box.speak(text: String(cString: text), maxFrames: maxFrames, seed: seed, callback: callback, arg: arg)
#else
    _ = engine
    _ = text
    _ = maxFrames
    _ = seed
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
    private let gate = NSLock()
    private var manager: PocketTtsManager?
    private var voiceData: PocketTtsVoiceData?
    init(voiceWav: String, temperature: Float, seed: Int32) {
        self.voiceWav = voiceWav
        self.temperature = temperature
        self.seed = seed
    }

    func speak(
        text: String,
        maxFrames: Int32,
        seed: Int32,
        callback: EmberVoiceSamplesFn?,
        arg: UnsafeMutableRawPointer?
    ) -> Int32 {
        // One speak at a time. Two overlapping calls would both miss the cache and clone twice.
        gate.lock()
        defer { gate.unlock() }
        let sem = DispatchSemaphore(value: 0)
        var produced = 0
        var failed = false
        let started = Date()
        var first: Date?
        let cap = maxFrames > 0 ? Int(maxFrames) * 1920 : Int.max
        let voiceURL = URL(fileURLWithPath: voiceWav)
        let temperature = self.temperature
        let cachedManager = manager
        let cachedVoice = voiceData
        var createdManager: PocketTtsManager?
        var createdVoice: PocketTtsVoiceData?
        Task.detached {
            defer { sem.signal() }
            do {
                let activeManager: PocketTtsManager
                let activeVoice: PocketTtsVoiceData
                if let cachedManager, let cachedVoice {
                    activeManager = cachedManager
                    activeVoice = cachedVoice
                } else {
                    let created = PocketTtsManager()
                    try await created.initialize()
                    // Clone the caller's clip once. Do not fall through to the
                    // bundled named voice; that clip is not the CC0 reference.
                    let cloned = try await created.cloneVoice(from: voiceURL)
                    createdManager = created
                    createdVoice = cloned
                    activeManager = created
                    activeVoice = cloned
                }
                let session = try await activeManager.makeSession(
                    voiceData: activeVoice,
                    temperature: temperature,
                    seed: UInt64(UInt32(bitPattern: seed))
                )
                session.enqueue(text)
                session.finish()
                for try await frame in session.frames {
                    let samples = frame.samples
                    if samples.isEmpty { continue }
                    if first == nil { first = Date() }
                    let room = cap - produced
                    if room <= 0 {
                        session.cancel()
                        break
                    }
                    let count = min(samples.count, room)
                    let keep = samples.withUnsafeBufferPointer { buffer -> Int32 in
                        guard let base = buffer.baseAddress else { return 1 }
                        return callback?(base, Int32(count), arg) ?? 1
                    }
                    produced += count
                    if keep == 0 || produced >= cap {
                        session.cancel()
                        break
                    }
                }
            } catch {
                failed = true
            }
        }
        sem.wait()
        if let createdManager, let createdVoice {
            manager = createdManager
            voiceData = createdVoice
        }
        if failed {
            let line = "ember voice: FluidAudio failed\n"
            FileHandle.standardError.write(Data(line.utf8))
        }
        lastSamples = Int32(clamping: produced)
        lastTtfaMs = (first ?? Date()).timeIntervalSince(started) * 1000.0
        return (!failed && produced > 0) ? 1 : 0
    }
}
#endif
