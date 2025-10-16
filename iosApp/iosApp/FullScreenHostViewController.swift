import UIKit
//  FullScreenHostViewController.swift
//  iosApp
//
//  Created by Timothy Westberg on 10/2/25.
//

final class FullScreenHostViewController: UIViewController {
    private let root: UIViewController
    
    init(root: UIViewController) {
        self.root = root
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder){
        fatalError("init(coder:) has not been implemented")
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
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
}
