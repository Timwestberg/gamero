import XCTest
@testable import EmberVoiceBench

final class BenchTests: XCTestCase {
    func testCorpusOnDevice() throws {
        let bundle = Bundle(for: BenchTests.self)
        guard let piecesURL = bundle.url(forResource: "pieces", withExtension: "json"),
              let configURL = bundle.url(forResource: "bench-config", withExtension: "json") else {
            XCTFail("pieces.json and bench-config.json must be in the test bundle. Run voice/ios-bench/run.sh.")
            return
        }
        let catalog = try JSONDecoder().decode(BenchCatalog.self, from: Data(contentsOf: piecesURL))
        let config = try JSONDecoder().decode(BenchConfig.self, from: Data(contentsOf: configURL))
        let bundledVoice = bundle.url(forResource: "reference-voice", withExtension: "wav")?.path
        let voicePath = bundledVoice ?? config.voice_wav
        if config.model_dir.isEmpty || voicePath.isEmpty {
            XCTFail("bench-config.json has empty paths. Run voice/ios-bench/run.sh on the Mac.")
            return
        }
        let synth: BenchSynthesizer
        if config.backend == "fluid-audio" {
            guard let engine = ember_voice_fluid_create(voicePath, Float(catalog.temperature), Int32(catalog.seed)) else {
                XCTFail("FluidAudio is not linked. Add the package, or leave the backend as sherpa.")
                return
            }
            defer { ember_voice_fluid_destroy(engine) }
            synth = FluidSynthesizer(engine: engine)
        } else {
            synth = SherpaSynthesizer(
                modelDir: config.model_dir,
                voiceWav: voicePath,
                threads: Int32(config.threads)
            )
        }
        let results = runBench(catalog: catalog, synth: synth)
        let json = benchJson(results: results)
        print(json)
        let attachment = XCTAttachment(data: Data(json.utf8), uniformTypeIdentifier: "public.json")
        attachment.name = "ember-voice-ios"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private final class FluidSynthesizer: BenchSynthesizer {
    let engine: UnsafeMutableRawPointer
    init(engine: UnsafeMutableRawPointer) { self.engine = engine }

    func speak(text: String, maxFrames: Int, seed: Int, steps: Int, temperature: Float) -> PieceTake {
        _ = seed
        _ = steps
        _ = temperature
        let code = text.withCString { cText in
            ember_voice_fluid_speak(engine, cText, Int32(maxFrames), nil, nil)
        }
        if code == 0 { fatalError("FluidAudio produced no audio for \(text)") }
        return PieceTake(
            ttfaMs: ember_voice_fluid_last_ttfa_ms(engine),
            samples: Int(ember_voice_fluid_last_samples(engine)),
            sampleRate: 24000
        )
    }
}
