import Foundation
import UIKit
import CoreHaptics

final class HapticsEngine {
    static let shared = HapticsEngine()

    private var engine: CHHapticEngine?
    private var isEnabled: Bool = true

    private init() {}

    func start() {
        prepareEngine()
        registerObservers()
    }

    private func prepareEngine() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics else { return }
        do {
            engine = try CHHapticEngine()
            try engine?.start()
        } catch {
            engine = nil
        }
    }

    private func registerObservers() {
        let center = NotificationCenter.default

        center.addObserver(forName: Notification.Name("onHapticUserReady"), object: nil, queue: .main) { [weak self] note in
            guard let enabled = note.userInfo?["enabled"] as? Bool else { return }
            self?.isEnabled = enabled
        }

        center.addObserver(forName: Notification.Name("onSetHapticFeedbackSetting"), object: nil, queue: .main) { [weak self] note in
            guard let enabled = note.userInfo?["enabled"] as? Bool else { return }
            self?.isEnabled = enabled
        }

        center.addObserver(forName: Notification.Name("onHapticFeedback"), object: nil, queue: .main) { [weak self] note in
            guard let effect = note.userInfo?["effect"] as? String else { return }
            self?.playPredefined(effect: effect)
        }

        center.addObserver(forName: Notification.Name("onPlayHapticEffect"), object: nil, queue: .main) { [weak self] note in
            guard let effect = note.userInfo?["effect"] as? String else { return }
            self?.playPredefined(effect: effect)
        }

        center.addObserver(forName: Notification.Name("onCustomHaptic"), object: nil, queue: .main) { [weak self] note in
            guard let segments = note.userInfo?["segments"] as? [[String: Any]] else { return }
            self?.playCustom(segments: segments)
        }
    }

    private func playPredefined(effect: String) {
        guard isEnabled else { return }

        // Try Core Haptics simple transient; fall back to UIFeedbackGenerators
        if let engine = engine {
            let intensity: Float
            let sharpness: Float
            switch effect.lowercased() {
            case "heavy", "failure", "error":
                intensity = 1.0; sharpness = 0.5
            case "medium", "warning":
                intensity = 0.6; sharpness = 0.4
            case "rigid":
                intensity = 0.7; sharpness = 1.0
            case "soft":
                intensity = 0.4; sharpness = 0.2
            case "selection", "success", "light":
                intensity = 0.4; sharpness = 0.5
            default:
                intensity = 0.4; sharpness = 0.5
            }
            let event = CHHapticEvent(eventType: .hapticTransient,
                                      parameters: [
                                        CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                                        CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness)
                                      ],
                                      relativeTime: 0)
            do {
                let pattern = try CHHapticPattern(events: [event], parameters: [])
                let player = try engine.makePlayer(with: pattern)
                try player.start(atTime: 0)
                return
            } catch { /* fall through to UI feedback */ }
        }

        // Fallback
        switch effect.lowercased() {
        case "success": UINotificationFeedbackGenerator().notificationOccurred(.success)
        case "warning": UINotificationFeedbackGenerator().notificationOccurred(.warning)
        case "error", "failure": UINotificationFeedbackGenerator().notificationOccurred(.error)
        case "heavy": UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        case "medium": UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case "light", "selection", "soft": UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case "rigid": if #available(iOS 13.0, *) { UIImpactFeedbackGenerator(style: .rigid).impactOccurred() } else { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        default: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    private func playCustom(segments: [[String: Any]]) {
        guard isEnabled else { return }
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics, let engine = engine else { return }

        var events: [CHHapticEvent] = []
        var time: TimeInterval = 0
        for seg in segments {
            let durationMs = (seg["durationMs"] as? NSNumber)?.doubleValue ?? 0
            let amplitude = (seg["amplitude"] as? NSNumber)?.doubleValue ?? 0
            let intensity = Float(min(max(amplitude / 255.0, 0), 1))
            let duration = durationMs / 1000.0
            let event = CHHapticEvent(eventType: .hapticContinuous,
                                      parameters: [
                                        CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                                        CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5)
                                      ],
                                      relativeTime: time,
                                      duration: duration)
            events.append(event)
            time += duration
        }
        do {
            let pattern = try CHHapticPattern(events: events, parameters: [])
            let player = try engine.makePlayer(with: pattern)
            try player.start(atTime: 0)
        } catch {
            // Ignore failure silently
        }
    }
}



