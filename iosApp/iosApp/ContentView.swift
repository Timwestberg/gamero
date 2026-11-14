import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        
        FullScreenHostViewController(root: MainViewControllerKt.MainViewController())
    }
    
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all, edges: .all)
            .statusBarHidden(true)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onAppear {
                // one-time setup if needed
            }
            // A sign-in code's link, handed over by the phone's camera (a universal link on the web app's own
            // address). Kotlin decides whether the web view may open it.
            .onOpenURL { url in
                MainViewControllerKt.openIncomingLink(url: url.absoluteString)
            }
    }
}
