

[Device Managers](#device-managers)

[DeepLink Manager](#deeplink-manager)

[Deeplink Web Processor Events](#deeplink-web-processor-events)

[Deeplink Manager Events](#deeplink-manager-events)

[Deeplink Manager Subscribers](#deeplink-manager-subscribers)

[Deeplink Manager Handlers](#deeplink-manager-handlers)

[Device Manager](#device-manager)

[Device Web Processor Events](#device-web-processor-events)

[Device Manager Events](#device-manager-events)

[Device Manager Subscribers](#device-manager-subscribers)

[Device Manager Handlers](#device-manager-handlers)

[DeviceInfo Storage](#deviceinfo-storage)

[ExternalLink Manager](#externallink-manager)

[External Link Web Processor Subscribers](#external-link-web-processor-subscribers)

[External Link Manager Events](#external-link-manager-events)

[External Link Manager Subscribers](#external-link-manager-subscribers)

[External Link Manager Handlers](#external-link-manager-handlers)

# Device Managers {#device-managers}

## DeepLink Manager {#deeplink-manager}

The Deep Link Manager handles inbound links to the Wrapper from external sources (push notifications, email, social, etc.). When a compatible link is tapped and the app is installed, the app is preferred over the browser. The manager parses the link, validates any sub-location or route parameters, and forwards a navigation intent through the Web Processor to the Web App so the correct in-app destination loads. If the player is not authenticated, it opens the Web App’s landing/sign-in view; deep-link routing is deferred until login or otherwise ignored per policy.

### Deeplink Web Processor Events {#deeplink-web-processor-events}

### Deeplink Manager Events {#deeplink-manager-events}

OnNewIntentEvent

### Deeplink Manager Subscribers {#deeplink-manager-subscribers}

OnNewIntentHandler

### Deeplink Manager Handlers {#deeplink-manager-handlers}

getBrazeDeepLinkingLocation  
getNavigationLocation  
onDeepLinking  
processAFDeepLink  
validateAndStoreAFAttributions  
goToAFDeeplink

## Device Manager {#device-manager}

The Device Manager collects, stores, and relays device metadata for diagnostics and feature checks. It maintains a canonical record, device model, OS and app version, locale, hardware flags—and device identifiers such as the advertising ID (ADID) and other platform IDs (subject to consent). This context is shared with the Web App so it can be surfaced where helpful (support, analytics, debugging). Aside from third-party SDKs, the Device Manager is our internal source of truth for device details, ensuring reproducible bug reports and consistent attribution across layers.

### Device Web Processor Events {#device-web-processor-events}

### Device Manager Events {#device-manager-events}

### Device Manager Subscribers {#device-manager-subscribers}

### Device Manager Handlers {#device-manager-handlers}

### DeviceInfo Storage {#deviceinfo-storage}

## ExternalLink Manager {#externallink-manager}

The External Link Manager routes URLs that should not load inside the WebView to the user’s default browser, preventing unintended in-WebView navigation and preserving in-app state so players can return exactly where they left off. Primary use cases include Zendesk support links, some supporting redirect-and-sign-in with the player’s WorldWinner account, as well as informational pages that are best viewed outside the app. By keeping the WebView anchored to the intended app surface, the manager maintains a predictable UX. It is intentionally lightweight today but can be extended to handle additional out-of-app actions (e.g., mailto:, tel:, store deep links, or partner handoffs) as needs evolve.

### External Link Web Processor Subscribers {#external-link-web-processor-subscribers}

### External Link Manager Events {#external-link-manager-events}

OnOpenExternalPageEvent

### External Link Manager Subscribers {#external-link-manager-subscribers}

onOpenExternalLinkHandler

### External Link Manager Handlers {#external-link-manager-handlers}

onOpenExternalLink

## Orientation Manager

### Orientation Web Processor Events

### Orientation Manager Events

OnLockOrientationEvent  
OnPlayingResponsiveWebGameEvent  
OnReturnFromGameEvent

### Orientation Manager Subscribers

OnLockOrientationHandler  
OnPlayingResponsiveWebGameHandler  
OnReturnFromGameHandler

### Orientation Manager Handlers

onHandleOrientationRequest  
onUpdateOrientation  
lockLandscapeOrientation  
lockPortraitOrientation  
allowAutoOrientation

# Technology