import Foundation

public struct BenchPiece: Decodable {
    let text: String
    let max_frames: Int
}

public struct BenchLine: Decodable {
    let id: String
    let text: String
    let pieces: [BenchPiece]
}

public struct BenchCatalog: Decodable {
    let silence_ms: Int
    let num_steps: Int
    let temperature: Double
    let seed: Int
    let lines: [BenchLine]
}

public struct BenchConfig: Decodable {
    let backend: String
    let model_dir: String
    let voice_wav: String
    let threads: Int
}

public struct PieceTake {
    let ttfaMs: Double
    let samples: Int
    let sampleRate: Int
}

public protocol BenchSynthesizer {
    func speak(text: String, maxFrames: Int, seed: Int, steps: Int, temperature: Float) -> PieceTake
}

public struct BenchLineResult {
    let id: String
    let ttfaMs: Double
    let totalMs: Double
    let audioSeconds: Double
    let rtf: Double
    let passedTtfa: Bool
    let passedRtf: Bool
}

public func runBench(catalog: BenchCatalog, synth: BenchSynthesizer) -> [BenchLineResult] {
    catalog.lines.map { line in
        let started = Date()
        var samples = 0
        var rate = 24000
        var ttfa = 0.0
        for (index, piece) in line.pieces.enumerated() {
            let take = synth.speak(
                text: piece.text,
                maxFrames: piece.max_frames,
                seed: catalog.seed,
                steps: catalog.num_steps,
                temperature: Float(catalog.temperature)
            )
            if index == 0 { ttfa = take.ttfaMs }
            samples += take.samples
            rate = take.sampleRate
            if index + 1 < line.pieces.count {
                samples += rate * catalog.silence_ms / 1000
            }
        }
        let totalMs = Date().timeIntervalSince(started) * 1000.0
        let audio = rate > 0 ? Double(samples) / Double(rate) : 0
        let rtf = totalMs > 0 ? audio / (totalMs / 1000.0) : 0
        return BenchLineResult(
            id: line.id,
            ttfaMs: ttfa,
            totalMs: totalMs,
            audioSeconds: audio,
            rtf: rtf,
            passedTtfa: ttfa <= 500.0,
            passedRtf: rtf >= 1.5
        )
    }
}

public func benchJson(results: [BenchLineResult]) -> String {
    var lines = results.map { row -> String in
        String(
            format: "    {\"id\": \"%@\", \"ttfa_ms\": %.1f, \"total_ms\": %.1f, \"audio_s\": %.3f, \"rtf\": %.2f, \"pass_ttfa\": %@, \"pass_rtf\": %@}",
            row.id,
            row.ttfaMs,
            row.totalMs,
            row.audioSeconds,
            row.rtf,
            row.passedTtfa ? "true" : "false",
            row.passedRtf ? "true" : "false"
        )
    }
    return "{\n  \"lines\": [\n" + lines.joined(separator: ",\n") + "\n  ]\n}\n"
}
