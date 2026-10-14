# host-bridge: strategy

- **Verdict: build.** No library gives frame and origin facts on JCEF, Android and iOS with one API. The desktop bridge already proves the pattern in this codebase.
- **Why now:** Hovel is the product. It runs on desktop with a bridge and on mobile with none. Each new native feature would otherwise be built three times in three styles.
- **Not now:** code generation, a plugin system, dynamic capability loading. Capabilities are compiled in.
- **Opportunity cost:** about 2 to 3 weeks of host work before new native features. It removes the 50 `expect fun` surface that each feature would add to.
