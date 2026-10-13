package org.formulagen.wrapper.companion

/** A packaged simulated launcher cannot be converted to a live launcher by inherited environment. */
enum class ProviderLaunchMode {
    CODEX, SIMULATED;

    companion object {
        fun current(): ProviderLaunchMode = parse(System.getProperty("gamero.provider.mode"), System.getenv("GAMERO_PROVIDER_MODE"),
            if (PackagedProviderMode.simulated) "simulated" else null)

        fun parse(property: String?, environment: String?, packaged: String? = null): ProviderLaunchMode {
            fun decode(value: String?): ProviderLaunchMode? = when (value) {
                null -> null
                "codex" -> CODEX
                "simulated" -> SIMULATED
                else -> error("Invalid provider launch mode")
            }
            val fixed = decode(packaged)
            val requested = listOfNotNull(fixed, decode(property), decode(environment)).distinct()
            require(requested.size <= 1) { "Conflicting provider launch modes" }
            return requested.singleOrNull() ?: CODEX
        }
    }
}
