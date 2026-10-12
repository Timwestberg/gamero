import SwiftUI
import FirebaseCore
import UIKit

@main
struct iOSApp: App {
    
    init() {
           FirebaseApp.configure()
           registerKotlinNotificationObservers()
           HapticsEngine.shared.start()
       }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private func registerKotlinNotificationObservers() {
    let center = NotificationCenter.default

    // Haptics: receive custom waveform instructions
    center.addObserver(forName: Notification.Name("onCustomHaptic"), object: nil, queue: .main) { note in
        // Placeholder: hook Core Haptics engine here if/when needed
        // Currently, shared Compose haptics handles simple cases; custom waveforms can be added later.
    }
}
