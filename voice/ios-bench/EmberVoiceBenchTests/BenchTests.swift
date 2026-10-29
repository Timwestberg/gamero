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
                threads: Int32(config.threads),
                chunkSize: Int32(config.chunk_size)
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
        _ = steps
        _ = temperature
        let started = Date()
        let code = text.withCString { cText in
            ember_voice_fluid_speak(engine, cText, Int32(maxFrames), Int32(seed), nil, nil)
        }
        let synthMs = Date().timeIntervalSince(started) * 1000.0
        if code == 0 { fatalError("FluidAudio produced no audio for \(text)") }
        return PieceTake(
            ttfaMs: ember_voice_fluid_last_ttfa_ms(engine),
            synthMs: synthMs,
            samples: Int(ember_voice_fluid_last_samples(engine)),
            sampleRate: 24000
        )
    }
}

private final class ScriptedSynth: BenchSynthesizer {
    var seeds = [Int]()
    var takes: [PieceTake]
    init(takes: [PieceTake]) { self.takes = takes }

    func speak(text: String, maxFrames: Int, seed: Int, steps: Int, temperature: Float) -> PieceTake {
        _ = text
        _ = maxFrames
        _ = steps
        _ = temperature
        seeds.append(seed)
        return takes.removeFirst()
    }
}

final class BenchLogicTests: XCTestCase {
    func testBufferedRetryCountsBothTakesAndKeepsTheShorter() {
        let synth = ScriptedSynth(takes: [
            PieceTake(ttfaMs: 100, synthMs: 120, samples: 24000 * 10, sampleRate: 24000),
            PieceTake(ttfaMs: 20, synthMs: 30, samples: 24000, sampleRate: 24000),
        ])
        let catalog = BenchCatalog(
            silenceBetweenPiecesMs: 40,
            numSteps: 1,
            fallbackNumSteps: 3,
            decoderFramesPerCallback: 2,
            longSentenceMaxChars: 400,
            temperature: 0.3,
            seed: 1,
            lines: [
                BenchLine(id: "done", text: "Done!", pieces: [BenchPiece(text: "Done!", max_frames: 36)]),
            ]
        )
        let row = runBench(catalog: catalog, synth: synth)[0]
        XCTAssertEqual(synth.seeds, [1, 1])
        XCTAssertEqual(row.ttfaMs, 150)
        XCTAssertEqual(row.audioSeconds, 1.0, accuracy: 0.001)
    }

    func testSilenceBetweenPiecesIsMeasured() {
        let synth = ScriptedSynth(takes: [
            PieceTake(ttfaMs: 12, synthMs: 12, samples: 2400, sampleRate: 24000),
            PieceTake(ttfaMs: 12, synthMs: 12, samples: 2400, sampleRate: 24000),
        ])
        let catalog = BenchCatalog(
            silenceBetweenPiecesMs: 40,
            numSteps: 1,
            fallbackNumSteps: 3,
            decoderFramesPerCallback: 2,
            longSentenceMaxChars: 400,
            temperature: 0.3,
            seed: 1,
            lines: [
                BenchLine(
                    id: "two",
                    text: "Done! Your build passed.",
                    pieces: [
                        BenchPiece(text: "Done!", max_frames: 36),
                        BenchPiece(text: "Your build passed.", max_frames: 36),
                    ]
                ),
            ]
        )
        let row = runBench(catalog: catalog, synth: synth)[0]
        XCTAssertEqual(row.ttfaMs, 12)
        XCTAssertEqual(row.audioSeconds, Double(2400 + 2400 + 960) / 24000.0, accuracy: 0.0001)
    }

    func testNoCeilingDoesNotRetry() {
        let synth = ScriptedSynth(takes: [
            PieceTake(ttfaMs: 12, synthMs: 40, samples: 24000 * 10, sampleRate: 24000),
        ])
        let catalog = BenchCatalog(
            silenceBetweenPiecesMs: 40,
            numSteps: 1,
            fallbackNumSteps: 3,
            decoderFramesPerCallback: 2,
            longSentenceMaxChars: 400,
            temperature: 0.3,
            seed: 1,
            lines: [
                BenchLine(id: "open", text: "Hello", pieces: [BenchPiece(text: "Hello", max_frames: 0)]),
            ]
        )
        let row = runBench(catalog: catalog, synth: synth)[0]
        XCTAssertEqual(synth.seeds, [1])
        XCTAssertEqual(row.ttfaMs, 12)
    }

}
