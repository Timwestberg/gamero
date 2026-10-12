

[Monitoring Managers](#monitoring-managers)

[Error Manager](#error-manager)

[Monitoring SDK (Sentry)](#monitoring-sdk-\(sentry\))

[Kotlin Multiplatform SDK](#kotlin-multiplatform-sdk)

[Error Web Processor Subscribers](#error-web-processor-subscribers)

[Error Manager Subscribers](#error-manager-subscribers)

[Error Manager Events](#error-manager-events)

[Error Manager Handlers](#error-manager-handlers)

[Error Manager Functions](#error-manager-delegates)

[Native Error Handlers](#native-error-handlers)

[Location Manager](#location-manager)

[Location Web Processor Subscribers](#location-web-processor-subscribers)

[Location Web Processor Events](#location-web-processor-events)

[Location Manager Subscribers](#location-manager-subscribers)

[Location Manager Events](#location-manager-events)

[Location Manager Handlers](#location-manager-handlers)

[Location Functions](#location-delegates)

[Technology](#technology)

# Monitoring Managers {#monitoring-managers}

## Error Manager {#error-manager}

The Error Manager centralizes breadcrumb and error monitoring at the Wrapper layer. Although the Wrapper is kept intentionally lightweight, failures can occur across managers, so Sentry is integrated via the native iOS and Android SDKs for each build. The manager lays breadcrumbs throughout key flows and captures unhandled and critical application errors, providing high-fidelity telemetry on user state and context. This reporting enables developers to diagnose hard-to-reproduce issues, prioritize the most impactful failures, and trace exactly where in the application flow a player encountered a problem.

### Monitoring SDK (Sentry) {#monitoring-sdk-(sentry)}

#### [Kotlin Multiplatform SDK](https://docs.sentry.io/platforms/kotlin/guides/kotlin-multiplatform/) {#kotlin-multiplatform-sdk}

### Error Web Processor Subscribers {#error-web-processor-subscribers}

### Error Manager Subscribers {#error-manager-subscribers}

#### Error Manager Events {#error-manager-events}

onSilentErrorEvent

#### Error Manager Handlers {#error-manager-handlers}

onSilentErrorHandler

#### Error Manager Delegates {#error-manager-delegates}

onSilentError

### Native Error Handlers  {#native-error-handlers}

## Location Manager {#location-manager}

The Location Manager requests, tracks, and relays the player’s location to the Web App. As the user navigates key areas, the Web App periodically requests location; when a cash-based feature requires legal eligibility, the manager forwards precise coordinates to the backend for verification. Based on the server’s decision, the application permits or gates access, while the Web App presents clear UI/UX to explain any restrictions and alternatives. If the player denies native location permissions, the system falls back to approximate IP-based geolocation (via the Web App) to determine eligibility, with the understanding that accuracy may be limited.

### Location Web Processor Subscribers {#location-web-processor-subscribers}

### Location Web Processor Events {#location-web-processor-events}

### Location Manager Subscribers {#location-manager-subscribers}

#### Location Manager Events {#location-manager-events}

#### Location Manager Handlers {#location-manager-handlers}

#### Location Delegates {#location-delegates}

# Technology {#technology}