# Spike 1: Home Assistant OS in a VM on the Mac

Part of [the Home Assistant link spec](https://github.com/Timwestberg/game-platform/blob/claude/feat/home-assistant-spec/docs/home-assistant/home-assistant.SPEC.md) (phase 0, decision 1). No product code changes.

The spike boots the official `generic-aarch64` Home Assistant OS image on Apple's Virtualization framework and answers six questions:

1. Does Home Assistant OS boot and serve `:8123` with NAT networking?
2. With NAT, can the Mac, the LAN and Home Assistant's discovery reach each other?
3. What does bridged networking need from Apple, and what does it change?
4. Can a USB radio (Zigbee, Z-Wave, Thread) reach the guest?
5. What happens when the Mac sleeps and wakes?
6. What does the VM cost in memory and CPU, and which macOS does it need?

## What we know before the run

- **Bridged networking needs a restricted entitlement.** [Apple's `VZBridgedNetworkDeviceAttachment` page](https://developer.apple.com/documentation/virtualization/vzbridgednetworkdeviceattachment) says an app must have `com.apple.vm.networking`, or the configuration is invalid. [Apple DTS says](https://developer.apple.com/forums/thread/656411) that you request it from Apple, that a paid developer account is needed, and that `validate()` fails without it even as root. So a Hovel build with bridged networking needs Apple's approval first.
- **NAT needs no restricted entitlement.** `VZNATNetworkDeviceAttachment` needs only `com.apple.security.virtualization`, which any signed app can have. We expect mDNS and discovery to stop at the NAT. The run checks this.
- **The APIs that the spike uses need macOS 13.** `VZEFIBootLoader` and `VZGenericPlatformConfiguration` are macOS 13 APIs. [havm](https://community.home-assistant.io/t/havm-native-home-assistant-os-on-apple-silicon-early-preview/1015138) needs macOS 27; we do not know why. Record the macOS version of each run.
- **USB passthrough.** We found no generic USB device passthrough in the Virtualization framework. The run confirms this. If it is true, a radio must be network-attached, or Home Assistant must run on its own hardware.

This code is not compiled in the cloud: Swift and the Virtualization framework are only on the Mac. The first `./run.sh` compiles it. Fix compile errors here, in this spike.

## Run it on the Mac

Use an Apple silicon Mac. Xcode or the command line tools give `swift` and `codesign`.

1. Download the image (about 400 MB to 1 GB, then grown to a sparse 32 GB file):

    ```sh
    ./setup.sh            # or ./setup.sh <version>
    ```

2. Boot it with NAT:

    ```sh
    ./run.sh --nat
    ```

3. In another terminal, find its address and wait for Home Assistant:

    ```sh
    ./find-ip.sh
    curl -s -o /dev/null -w '%{http_code}\n' http://<address>:8123/
    ```

4. Check discovery from the Mac: `dns-sd -B _home-assistant._tcp local.`. Check from a phone on the same Wi-Fi: open `http://<address>:8123`.
5. Sleep the Mac (`pmset sleepnow`), wake it, and check `:8123` and the Home Assistant uptime again.
6. Plug in a USB radio. Look for it in Home Assistant (Settings → System → Hardware).
7. Note the memory and CPU of "Virtualization Service" in Activity Monitor after 10 minutes at idle.
8. Try bridged networking. List interfaces with `./run.sh interfaces`, then:

    ```sh
    ./run.sh --bridged en0
    ```

    Ad-hoc signing with `com.apple.vm.networking` is expected to fail. Record the exact error. With a Developer ID identity that holds the entitlement, set `SIGN_IDENTITY` and run it again.

Ctrl-C asks the guest to shut down. A second Ctrl-C stops the VM at once. `work/` holds the image, the EFI variables and the MAC address, and git ignores it.

## Results

| Check | Result | Notes |
| --- | --- | --- |
| macOS version, Mac model | | |
| Home Assistant OS version | | |
| NAT: boots, time until `:8123` answers | | |
| NAT: Mac reaches `:8123` | | |
| NAT: phone on the LAN reaches `:8123` | | |
| NAT: Mac sees `_home-assistant._tcp` | | |
| NAT: Home Assistant discovers LAN devices | | |
| Bridged, ad hoc: error | | |
| Bridged, with the entitlement | | |
| USB radio in the guest | | |
| Sleep and wake | | |
| Memory and CPU at idle | | |
| Image SHA-256 checked | | |

## What it decides

- If NAT boots but discovery fails, and bridged needs Apple's entitlement, decision 1 has three paths: request the entitlement for Hovel's helper, ship NAT with a port forward and no discovery, or fall back to a guided install.
- If no USB radio reaches the guest, the install sheet says so, and it suggests network radios or dedicated hardware.

Write the recommendation here after the runs.
