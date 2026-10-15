# host-bridge: test strategy

1. **Contract fixtures** (event-contracts): every `valid/` parses, every `invalid/` fails with the expected code.
2. **Router** (commonTest, runs on JVM, Android unit, iOS simulator): the same fixtures; plus context expiry, cancel on navigation, `busy`, `unsupported`, thrown capability → `failed`.
3. **Parity**: each platform's `HostCapabilities` lists exactly the methods of the parity table.
4. **Transports**: one test per transport with a fake WebView where the platform allows (JCEF has one now); otherwise the smoke page.
5. **Smoke page** on desktop, Android debug and iOS simulator through Remote Control. Pass = same lines as the expected file, except `unsupported` cells.
6. **Regression**: `docs/hovel-native-validation.md` section 1 on desktop after A3 and after B4.
