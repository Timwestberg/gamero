// Spike 1: run the official Home Assistant OS generic-aarch64 image in a Virtualization framework VM.
//
//   HaVmSpike interfaces
//   HaVmSpike run --image work/haos.img --state work/state [--nat | --bridged en0] [--cpus 2] [--memory-gb 2]
//
// Ctrl-C asks the guest to shut down. A second Ctrl-C stops the VM at once.

import Foundation
import Virtualization

enum NetworkMode: CustomStringConvertible {
    case nat
    case bridged(String)

    var description: String {
        switch self {
        case .nat: return "NAT"
        case .bridged(let name): return "bridged to \(name)"
        }
    }
}

struct Options {
    var image: URL?
    var state = URL(fileURLWithPath: "work/state")
    var network = NetworkMode.nat
    var cpus = 2
    var memoryGB: UInt64 = 2
}

enum SpikeError: Error, CustomStringConvertible {
    case noInterface(String)

    var description: String {
        switch self {
        case .noInterface(let name):
            return "No bridgeable interface named \(name). Run `HaVmSpike interfaces` to list them."
        }
    }
}

func log(_ message: String) {
    print("[\(ISO8601DateFormatter().string(from: Date()))] \(message)")
    fflush(stdout)
}

func usage() -> Never {
    let text = """
    usage: HaVmSpike interfaces
           HaVmSpike run --image <haos.img> [--state <dir>] [--nat | --bridged <interface>] [--cpus N] [--memory-gb N]

    """
    FileHandle.standardError.write(Data(text.utf8))
    exit(64)
}

func parse(_ arguments: [String]) -> Options {
    var options = Options()
    var rest = arguments[...]
    while let flag = rest.popFirst() {
        switch flag {
        case "--image":
            guard let value = rest.popFirst() else { usage() }
            options.image = URL(fileURLWithPath: value)
        case "--state":
            guard let value = rest.popFirst() else { usage() }
            options.state = URL(fileURLWithPath: value)
        case "--nat":
            options.network = .nat
        case "--bridged":
            guard let value = rest.popFirst() else { usage() }
            options.network = .bridged(value)
        case "--cpus":
            guard let value = rest.popFirst(), let count = Int(value) else { usage() }
            options.cpus = count
        case "--memory-gb":
            guard let value = rest.popFirst(), let size = UInt64(value) else { usage() }
            options.memoryGB = size
        default:
            usage()
        }
    }
    return options
}

/// The same MAC address on every run, so DHCP gives the VM the same address and Home Assistant keeps its identity.
func macAddress(in state: URL) -> VZMACAddress {
    let file = state.appendingPathComponent("mac-address")
    if let saved = try? String(contentsOf: file, encoding: .utf8),
       let address = VZMACAddress(string: saved.trimmingCharacters(in: .whitespacesAndNewlines)) {
        return address
    }
    let address = VZMACAddress.randomLocallyAdministered()
    try? address.string.write(to: file, atomically: true, encoding: .utf8)
    return address
}

/// Home Assistant OS boots with UEFI, so the VM keeps its EFI variables between runs.
func variableStore(in state: URL) throws -> VZEFIVariableStore {
    let file = state.appendingPathComponent("efi-variables")
    if FileManager.default.fileExists(atPath: file.path) {
        return VZEFIVariableStore(url: file)
    }
    return try VZEFIVariableStore(creatingVariableStoreAt: file, options: [])
}

func configuration(_ options: Options, image: URL) throws -> VZVirtualMachineConfiguration {
    let config = VZVirtualMachineConfiguration()
    config.platform = VZGenericPlatformConfiguration()

    let bootLoader = VZEFIBootLoader()
    bootLoader.variableStore = try variableStore(in: options.state)
    config.bootLoader = bootLoader

    config.cpuCount = options.cpus
    config.memorySize = options.memoryGB * 1024 * 1024 * 1024

    let disk = try VZDiskImageStorageDeviceAttachment(url: image, readOnly: false)
    config.storageDevices = [VZVirtioBlockDeviceConfiguration(attachment: disk)]

    let network = VZVirtioNetworkDeviceConfiguration()
    network.macAddress = macAddress(in: options.state)
    switch options.network {
    case .nat:
        network.attachment = VZNATNetworkDeviceAttachment()
    case .bridged(let name):
        // Needs the com.apple.vm.networking entitlement; without it, validate() below fails.
        guard let interface = VZBridgedNetworkInterface.networkInterfaces.first(where: { $0.identifier == name }) else {
            throw SpikeError.noInterface(name)
        }
        network.attachment = VZBridgedNetworkDeviceAttachment(interface: interface)
    }
    config.networkDevices = [network]

    config.entropyDevices = [VZVirtioEntropyDeviceConfiguration()]
    config.memoryBalloonDevices = [VZVirtioTraditionalMemoryBalloonDeviceConfiguration()]

    // Guest console output, if Home Assistant OS writes to the virtio console (hvc0). It can stay empty.
    let consoleLog = options.state.appendingPathComponent("console.log")
    FileManager.default.createFile(atPath: consoleLog.path, contents: nil)
    let serial = VZVirtioConsoleDeviceSerialPortConfiguration()
    serial.attachment = VZFileHandleSerialPortAttachment(
        fileHandleForReading: nil,
        fileHandleForWriting: try FileHandle(forWritingTo: consoleLog)
    )
    config.serialPorts = [serial]

    try config.validate()
    return config
}

final class Observer: NSObject, VZVirtualMachineDelegate {
    func guestDidStop(_ virtualMachine: VZVirtualMachine) {
        log("guest shut down")
        exit(0)
    }

    func virtualMachine(_ virtualMachine: VZVirtualMachine, didStopWithError error: Error) {
        log("VM stopped with an error: \(error)")
        exit(1)
    }

    func virtualMachine(_ virtualMachine: VZVirtualMachine, networkDevice: VZNetworkDevice,
                        attachmentWasDisconnectedWithError error: Error) {
        log("network attachment disconnected: \(error)")
    }
}

// Kept alive for the life of the process.
var machine: VZVirtualMachine?
let observer = Observer()
var stopRequested = false

func run(_ options: Options) {
    guard let image = options.image else { usage() }
    do {
        try FileManager.default.createDirectory(at: options.state, withIntermediateDirectories: true)
        let config = try configuration(options, image: image)
        let vm = VZVirtualMachine(configuration: config)
        vm.delegate = observer
        machine = vm
        let address = macAddress(in: options.state).string
        log("starting: \(options.cpus) CPUs, \(options.memoryGB) GB, \(options.network), MAC \(address)")
        vm.start { result in
            switch result {
            case .success:
                log("running. Find its address with ./find-ip.sh, then open http://<address>:8123")
            case .failure(let error):
                log("start failed: \(error)")
                exit(1)
            }
        }
    } catch {
        log("configuration refused: \(error)")
        exit(1)
    }

    signal(SIGINT, SIG_IGN)
    let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
    interrupt.setEventHandler {
        guard let vm = machine else { exit(0) }
        if !stopRequested, vm.canRequestStop {
            stopRequested = true
            log("asking the guest to shut down (Ctrl-C again stops it at once)")
            try? vm.requestStop()
        } else {
            log("stopping the VM")
            vm.stop { _ in exit(0) }
        }
    }
    interrupt.resume()
    dispatchMain()
}

let arguments = Array(CommandLine.arguments.dropFirst())
switch arguments.first {
case "interfaces":
    for interface in VZBridgedNetworkInterface.networkInterfaces {
        print("\(interface.identifier)\t\(interface.localizedDisplayName ?? "")")
    }
case "run":
    run(parse(Array(arguments.dropFirst())))
default:
    usage()
}
