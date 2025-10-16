import Foundation
import WebKit


final class RemoteConfig {
    static func start() {

            let center: NotificationCenter = NotificationCenter.default

            center.addObserver(forName: Notification.Name("onFetchRemoteConfig"), object: nil, queue: .main) { note in
                print("onActivateRemoteConfig")

            }

            center.addObserver(forName: Notification.Name("onVerifyManagers"), object: nil, queue: .main) { note in
            print("onVerifyManagers")

            }
        }
}