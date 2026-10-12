import UIKit
//  FullScreenHostViewController.swift
//  iosApp
//
//  Created by Timothy Westberg on 10/2/25.
//

final class FullScreenHostViewController: UIViewController {
    private let root: UIViewController
    static var current: FullScreenHostViewController?
    static var orientationMask: UIInterfaceOrientationMask = .portrait
    
    init(root: UIViewController) {
        self.root = root
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder){
        fatalError("init(coder:) has not been implemented")
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        FullScreenHostViewController.current = self
        addChild(root)
        root.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root.view)
        NSLayoutConstraint.activate([
            root.view.topAnchor.constraint(equalTo: view.topAnchor),
            root.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            root.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            root.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        root.didMove(toParent: self)
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return FullScreenHostViewController.orientationMask
    }

    func applyOrientation(_ mask: UIInterfaceOrientationMask) {
        FullScreenHostViewController.orientationMask = mask
        setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}
