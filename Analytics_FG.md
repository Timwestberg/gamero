

[Analytics/Marketing Managers](#analytics/marketing-managers)

[Amplitude Manager](#amplitude-manager)

[Native Amplitude SDKs](#native-amplitude-sdks)

[iOS Amplitude SDK](#ios-amplitude-sdk)

[Android Amplitude SDK](#android-amplitude-sdk)

[Amplitude Manager Web Processor Events](#amplitude-manager-web-processor-events)

[logAmplitude](#logamplitude)

[Amplitude Subscribers](#amplitude-subscribers)

[Amplitude Manager Events](#amplitude-manager-events)

[OnResponsiveWebReadyEvent](#onresponsivewebreadyevent)

[OnUserLogoutEvent](#onuserlogoutevent)

[OnUpdateCatalogContentEvent](#onupdatecatalogcontentevent)

[OnGameEndedEvent](#ongameendedevent)

[OnSilentErrorEvent](#onsilenterrorevent)

[OnAnalyticsQueueEvent](#onanalyticsqueueevent)

[OnLogAmplitudeEvent](#onlogamplitudeevent)

[Amplitude Manager Handlers](#amplitude-manager-handlers)

[onResponsiveWebReadyHandler](#onresponsivewebreadyhandler)

[onUserReadyHandler](#onuserreadyhandler)

[onUserLogoutHandler](#onuserlogouthandler)

[onUpdateCatalogContentHandler](#onupdatecatalogcontenthandler)

[onStartGameHandler](#onstartgamehandler)

[onGameEndedHandler](#ongameendedhandler)

[onSilentErrorHandler](#onsilenterrorhandler)

[onAnalyticsQueueHandler](#onanalyticsqueuehandler)

[onLogAmplitudeEventHandler](#onlogamplitudeeventhandler)

[Amplitude Manager Delegates](#amplitude-manager-delegates)

[logAmplitudeEvent](#logamplitudeevent)

[setUserProperties](#setuserproperties)

[Amplitude Kotlin Processor Events](#amplitude-kotlin-processor-events)

[Amplitude Native Subscribers](#amplitude-native-subscribers)

[Amplitude Native Handlers](#amplitude-native-handlers)

[AppsFlyer Manager](#appsflyer-manager)

[Native AppsFlyer SDKs](#native-appsflyer-sdks)

[iOS AppsFlyer SDK](#ios-appsflyer-sdk)

[Android AppsFlyer SDK](#android-appsflyer-sdk)

[AppsFlyer Manager Web Processor Events](#appsflyer-manager-web-processor-events)

[AppsFlyer Manager Subscribers](#appsflyer-manager-subscribers)

[AppsFlyer Manager Events](#appsflyer-manager-events)

[OnInstallEvent](#oninstallevent)

[OnUserReadyEvent](#onuserreadyevent)

[OnUserLogoutEvent](#onuserlogoutevent-1)

[OnLogAFEvent](#onlogafevent)

[OnGameStartedEvent](#ongamestartedevent)

[AppsFlyer Manager Handlers](#appsflyer-manager-handlers)

[onInstallHandler](#oninstallhandler)

[onUserReadyHandler](#onuserreadyhandler-1)

[onUserLogoutHandler](#onuserlogouthandler-1)

[onLogAFHandler](#onlogafhandler)

[AppsFlyer Manager Delegates](#appsflyer-manager-delegates)

[setUserId](#setuserid)

[logAFEvent](#logafevent)

[AppsFlyer Kotlin Processor Events](#appsflyer-kotlin-processor-events)

[AppsFlyer Manager Native Subscribers](#appsflyer-manager-native-subscribers)

[AppsFlyer Manager Native Handlers](#appsflyer-manager-native-handlers)

[Braze Manager](#braze-manager)

[Native Braze SDKs](#native-braze-sdks)

[iOS Braze SDK](#ios-braze-sdk)

[Android Braze SDK](#android-braze-sdk)

[Braze Manager Web Processor Events](#braze-manager-web-processor-events)

[Braze Manager Subscribers](#braze-manager-subscribers)

[Braze Manager Events](#braze-manager-events)

[OnUserReadyEvent](#onuserreadyevent-1)

[OnUserLogoutEvent](#onuserlogoutevent-2)

[OnPushNotificationStateChangeEvent](#onpushnotificationstatechangeevent)

[Braze Manager Handlers](#braze-manager-handlers)

[onUserReadyHandler](#onuserreadyhandler-2)

[onUserLogoutHandler](#onuserlogouthandler-2)

[onPushNotificationStateChangeHandler](#onpushnotificationstatechangehandler)

[Braze Manager Delegates](#braze-manager-delegates)

[setBrazeInitializationArgs](#setbrazeinitializationargs)

[setBrazeUser](#setbrazeuser)

[setPushSubscription](#setpushsubscription)

[setTestDevice](#settestdevice)

[createUriActionFromUri](#createuriactionfromuri)

[createUriActionFromUrlString](#createuriactionfromurlstring)

[getIntentFlags](#getintentflags)

[gotoNewsFeed](#gotonewsfeed)

[gotoUri](#gotouri)

[Braze Kotlin Processor Events](#braze-kotlin-processor-events)

[Braze Manager Native Subscribers](#braze-manager-native-subscribers)

[Braze Manager Native Handlers](#braze-manager-native-handlers)

[Technology](#technology)

# Analytics/Marketing Managers {#analytics/marketing-managers}

![][image1]

## Amplitude Manager {#amplitude-manager}

The Amplitude Manager handles analytics funnel events emitted by the Web App, enabling product and analytics teams to understand player behavior and evaluate feature impact. Because Amplitude lacks a Kotlin Multiplatform SDK, the Wrapper integrates the native iOS and Android SDKs and acts as a pass-through: a generic logger accepts an eventName and eventData from the Web Processor and forwards them to the platform SDKs so expected device and customer context is attached. This approach provides richer telemetry and more reliable attribution than sending events solely through the Web SDK.

### Native Amplitude SDKs {#native-amplitude-sdks}

#### [iOS Amplitude SDK](https://amplitude.com/docs/sdks/analytics/ios/unified-sdk) {#ios-amplitude-sdk}

#### [Android Amplitude SDK](https://amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk) {#android-amplitude-sdk}

### Amplitude Manager Web Processor Events  {#amplitude-manager-web-processor-events}

#### logAmplitude {#logamplitude}

### Amplitude Subscribers {#amplitude-subscribers}

#### Amplitude Manager Events {#amplitude-manager-events}

##### OnResponsiveWebReadyEvent {#onresponsivewebreadyevent}

Called when the responsive web is ready to Registers the Amplitude session listener plugin

```kotlin
/** Indicates that the responsive web view is ready. */
data class OnResponsiveWebReadyEvent(val isReady: Boolean = true)
```

OnUserReadyEvent  
Called when the user is ready to set the Amplitude user ID

```kotlin
/** Indicates that the user is ready. */
data class OnUserReadyEvent(val data: DeviceEvent.UserReady)

data class UserReady(
    val preferences: Preferences, // The user's preferences for music, sound effects, and vibrations
    val user: User, // The user object containing userId
  )

  // Data class for the "preferences" field
 data class Preferences(
    val music: Boolean, // Whether music is enabled
    val soundEffects: Boolean, // Whether sound effects are enabled
    val vibrations: Boolean, // Whether vibrations are enabled
  )

  // Data class for the "user" field
  data class User(
    val userId: String, // The user's unique ID
    val userName: String, // the User's Unique username
    val legacyUserId: Int?, // the User's Legacy ID
    val brazeExternalId: String, // The Braze analytics user id
  )
```

##### OnUserLogoutEvent {#onuserlogoutevent}

Handles user logout by flushing Amplitude data and resetting the instance.

```kotlin
/** Triggered when the user logs out. */
data class OnUserLogoutEvent(val username: String?)
```

##### OnUpdateCatalogContentEvent {#onupdatecatalogcontentevent}

Called when the catalog content is updated to set the catalog ID and version

```kotlin
/** Event posted when the catalog content has been updated */
data class OnUpdateCatalogContentEvent(val catalogVersionId: String, val catalogVersion: Int)
```

OnStartGameEvent  
Called when the game is started to log the event to Amplitude

```kotlin
/** Starts a game within a tournament. */
data class OnStartGameEvent(val o: String, val tournamentInstanceEntryId: String)
```

##### OnGameEndedEvent {#ongameendedevent}

Called when the game is ended to log the event to Amplitude.

```kotlin
/** Indicates that a game has ended. */
data class OnGameEndedEvent(val tournamentId: String)
```

##### OnSilentErrorEvent {#onsilenterrorevent}

Logs a special error event within Amplitude.

```kotlin
data class OnSilentErrorEvent(
  val tag: String,
  val sentryLevel: SentryLevel,
  val errorLevel: ErrorLevels,
  val message: String,
  val error: Exception,
  val errorCode: Int = 0,
  val stackTrace: String? = null,
)
```

##### OnAnalyticsQueueEvent {#onanalyticsqueueevent}

Subscribes to \`OnAnalyticsQueueEvent\`, parses the event data, and logs it to Amplitude

```kotlin
/** Queues an analytics event for processing. */
data class OnAnalyticsQueueEvent(val eventName: String?, val eventObject: String?)
```

##### OnLogAmplitudeEvent {#onlogamplitudeevent}

Logs an event to Amplitude

```kotlin
/** Logs an event to Amplitude. */
data class OnLogAmplitudeEvent(val eventName: String, val eventData: Map<String, Any>?)
```

#### Amplitude Manager Handlers {#amplitude-manager-handlers}

##### onResponsiveWebReadyHandler {#onresponsivewebreadyhandler}

```kotlin
/** Responsive web is ready: share the current Amplitude session info and attach the session listener once. */
fun onResponsiveWebReadyHandler(event: OnResponsiveWebReadyEvent) {
  logger.d(TAG, "OnResponsiveWebReadyEvent: $event")

  // If a session already exists, forward its identifiers to the web layer.
  val sessionId = amplitudeInstance.sessionId
  if (sessionId != INVALID_SESSION_ID) {
    val deviceId = amplitudeInstance.getDeviceId() // only fetched when needed
    EventBus.getDefault().post(OnAmplitudeSessionIdAndDeviceIdSet(sessionId, deviceId))
  } else {
    logger.d(TAG, "Amplitude session not ready (id=$sessionId); will notify when session starts.")
  }

  // Register the session listener plugin exactly once to observe future session changes.
  if (!isSessionPluginRegistered) {
    amplitudeInstance.add(AmplitudeSessionListenerPlugin(logger))
    isSessionPluginRegistered = true
    logger.d(TAG, "AmplitudeSessionListenerPlugin registered.")
  }
}

private companion object {
  private const val INVALID_SESSION_ID = -1L
}
```

##### onUserReadyHandler {#onuserreadyhandler}

```kotlin
/** User became available; cache + sync to Amplitude. */
fun onUserReadyHandler(event: OnUserReadyEvent) {
  logger.d(TAG, "OnUserReadyEvent: $event")
  val user = event.data.user
  setUserProperties(user.userId, user.userName)
}
```

##### onUserLogoutHandler {#onuserlogouthandler}

```kotlin
/** Logout: flush pending analytics and reset the SDK instance. */
fun onUserLogoutHandler(event: OnUserLogoutEvent) {
  logger.d(TAG, "OnUserLogoutEvent: $event")
  amplitudeInstance.flush()
  amplitudeInstance.reset()
}
```

##### onUpdateCatalogContentHandler {#onupdatecatalogcontenthandler}

```kotlin
/** Catalog content updated: update system properties used for all subsequent events. */
fun onUpdateCatalogContentHandler(event: OnUpdateCatalogContent) {
  amplitudeSystemProperties.apply {
    put(AMPLITUDE_SYSTEM_PROPERTY_CATALOG_ID, event.catalogVersionId)
    put(AMPLITUDE_SYSTEM_PROPERTY_CATALOG_VERSION, event.catalogVersion)
  }
  logger.d(
    TAG,
    "Updated catalog properties: id=${event.catalogVersionId}, version=${event.catalogVersion}"
  )
}
```

##### onStartGameHandler {#onstartgamehandler}

```kotlin
/** Game started: log with tournament identifier. */
fun onStartGameHandler(event: OnStartGameEvent) {
  logger.d(TAG, "OnStartGameEvent: $event")

  val props = mapOf(
    AMPLITUDE_EVENT_PROPERTY_TOURNAMENT_ID to event.tournamentInstanceEntryId
  )
  logAmplitudeEvent(AMPLITUDE_EVENT_GAME_STARTED, props)
}
```

##### onGameEndedHandler {#ongameendedhandler}

```kotlin
/** Game ended: log with tournament ID. */
fun onGameEndedHandler(event: OnGameEndedEvent) {
  logger.d(TAG, "OnGameEndedEvent: $event")
  val props = mapOf(
    AMPLITUDE_EVENT_PROPERTY_TOURNAMENT_ID to event.tournamentId
  )
  logAmplitudeEvent(AMPLITUDE_EVENT_GAME_ENDED, props)
}
```

##### onSilentErrorHandler {#onsilenterrorhandler}

```kotlin
/**
 * Forwards a silent error to Amplitude.
 *
 * Behavior :
 * - Always logs the incoming event.
 * - Sends AMPLITUDE_EVENT_APP_ERROR with the same fields as before.
 * - Omits optional fields if they are null (prevents nulls in the map).
 */
fun onSilentErrorHandler(event: OnSilentErrorEvent) {
  logger.d(TAG, "OnSilentErrorEvent: $event")

  val stackTrace = event.stackTrace.orEmpty()

  // Build event properties; only include optional fields when present.
  val props = buildMap<String, Any> {
    event.tag?.let { put("TAG", it) }
    event.message?.let { put("message", it) }
    event.error?.let { put("error", it) }          // keep raw value; same behavior
    event.errorLevel?.let { put("errorLevel", it) }
    put("stackTrace", stackTrace)                   // always include (may be empty)
    put("release", BuildConfig.VERSION_NAME)
    put("buildVersion", BuildConfig.VERSION_CODE)
  }

  logAmplitudeEvent(AMPLITUDE_EVENT_APP_ERROR, props)
}

```

##### onAnalyticsQueueHandler {#onanalyticsqueuehandler}

```kotlin
/**
 * Handles OnAnalyticsQueueEvent by parsing optional JSON and forwarding to Amplitude.
 *
 * Behavior :
 * - If eventName is null → warn and return.
 * - If eventObject is present, attempt to parse to Map<String, Any>; on failure, log and proceed with null.
 * - Delegate to logAmplitudeEvent(name, parsedOrNull).
 */
fun onAnalyticsQueueHandler(event: OnAnalyticsQueueEvent) {
  // Guard: event name must exist; blank names are handled downstream by logAmplitudeEvent.
  val name = event.eventName ?: run {
    logger.w(TAG, "Event name is null; not logging to Amplitude.")
    return
  }

  // Always log what we received for traceability.
  logger.d(TAG, "Received event: $name with data: ${event.eventObject}")

  // Parse JSON only when provided; keep null on failure (original behavior).
  val eventData: Map<String, Any>? = event.eventObject?.let { raw ->
    runCatching { JSON.fromJsonToMap(raw) }
      .onFailure { e -> logger.e(TAG, "Failed to parse eventObject JSON: $raw", e) }
      .getOrNull()
  }

  // Forward to the existing logger; it decides how to handle null/blank cases.
  logAmplitudeEvent(name, eventData)
}

```

##### onLogAmplitudeEventHandler {#onlogamplitudeeventhandler}

```kotlin
 fun onLogAmplitudeEventHandler(event: OnLogAmplitudeEvent) {
    logAmplitudeEvent(eventName = event.eventName, eventData = event.eventData)
  }
```

#### Amplitude Manager Delegates {#amplitude-manager-delegates}

##### logAmplitudeEvent {#logamplitudeevent}

```kotlin
/**
 * Logs an Amplitude event if the name is non-blank.
 *
 * Behavior:
 * - If [eventData] is non-null, merge it with [amplitudeSystemProperties].
 *   On key collisions, amplitudeSystemProperties take precedence.
 * - If [eventData] is null, send the event with NO properties at all.
 */
private fun logAmplitudeEvent(
  eventName: String,
  eventData: Map<String, Any>?
) {
  val name = eventName.trim()
  if (name.isEmpty()) {
    logger.w(TAG, "Event name is blank; skipping Amplitude log.")
    return
  }

  // Only merge when eventData is provided (preserves original behavior).
  // For Map.plus, the right-hand side wins on key collisions.
  val props: Map<String, Any>? = eventData?.let { it + amplitudeSystemProperties }

  props?.let { merged ->
    logger.d(TAG, "Logging Amplitude event: $name, properties=$merged")
    amplitudeInstance.track(name, merged)
  } ?: run {
    logger.d(TAG, "Logging Amplitude event: $name (no properties)")
    amplitudeInstance.track(name)
  }
}

```

##### setUserProperties {#setuserproperties}

```kotlin
/**
 * Updates locally cached userId/userName and syncs them to Amplitude + app events.
 *
 * Behavior :
 * - No-op if BOTH values are unchanged.
 * - If userId changed: update cache, log, call amplitude.setUserId, post OnSetWWUserIdEvent.
 * - If userName changed: update cache, log, post OnSetUsernameEvent.
 * - If either changed: send an Amplitude Identify with BOTH id and username.
 */
private fun setUserProperties(userId: String, userName: String) {
  // Determine what actually changed first (avoids duplicate equality checks).
  val idChanged = this.userId != userId
  val nameChanged = this.userName != userName

  // Nothing to do if both are already up to date.
  if (!idChanged && !nameChanged) return

  // Update and broadcast changes independently to retain original side-effects.
  if (idChanged) {
    this.userId = userId
    logger.d(TAG, "Setting Amplitude user ID: $userId")
    amplitudeInstance.setUserId(userId)
    EventBus.post(OnSetWWUserIdEvent(userId = userId))
  }

  if (nameChanged) {
    this.userName = userName
    logger.d(TAG, "Setting Username: $userName")
    EventBus.post(OnSetUsernameEvent(username = userName))
  }

  // Push user properties to Amplitude whenever any field changed.
  logger.d(TAG, "Setting Amplitude user properties: id=$userId, username=$userName")
  val identify = Identify().apply {
    set(AMPLITUDE_IDENTITY_PROPERTY_ID, userId)
    set(AMPLITUDE_IDENTITY_PROPERTY_USERNAME, userName)
  }
  amplitudeInstance.identify(identify)
}

```

### Amplitude Kotlin Processor Events {#amplitude-kotlin-processor-events}

### Amplitude Native Subscribers {#amplitude-native-subscribers}

### Amplitude Native Handlers {#amplitude-native-handlers}

## AppsFlyer Manager {#appsflyer-manager}

The AppsFlyer Manager processes attribution events from the Web App, giving product and analytics teams visibility into acquisition sources and how player behavior varies by channel, informing campaign targeting and measuring partner performance for new and updated campaigns. Since AppsFlyer has no Kotlin Multiplatform SDK, the Wrapper integrates the native iOS and Android SDKs. The manager acts as a pass-through: a generic logger accepts an eventName and eventData from the Web Processor and forwards them to the platform SDKs so richer telemetry and attribution metadata are attached.

### Native AppsFlyer SDKs {#native-appsflyer-sdks}

#### [iOS AppsFlyer SDK](https://dev.appsflyer.com/hc/docs/ios-sdk) {#ios-appsflyer-sdk}

#### [Android AppsFlyer SDK](https://dev.appsflyer.com/hc/docs/android-sdk) {#android-appsflyer-sdk}

### AppsFlyer Manager Web Processor Events  {#appsflyer-manager-web-processor-events}

### AppsFlyer Manager Subscribers {#appsflyer-manager-subscribers}

#### AppsFlyer Manager Events {#appsflyer-manager-events}

##### OnInstallEvent {#oninstallevent}

Indicates that the user has seen the onboarding screen for the first time.

```kotlin
data class OnInstallEvent(val isInstalled: Boolean = true)
```

##### OnUserReadyEvent {#onuserreadyevent}

Indicates that the user is ready.

```kotlin
/** Indicates that the user is ready. */
data class OnUserReadyEvent(val data: DeviceEvent.UserReady)

data class UserReady(
    val preferences: Preferences, // The user's preferences for music, sound effects, and vibrations
    val user: User, // The user object containing userId
  )

  // Data class for the "preferences" field
 data class Preferences(
    val music: Boolean, // Whether music is enabled
    val soundEffects: Boolean, // Whether sound effects are enabled
    val vibrations: Boolean, // Whether vibrations are enabled
  )

  // Data class for the "user" field
  data class User(
    val userId: String, // The user's unique ID
    val userName: String, // the User's Unique username
    val legacyUserId: Int?, // the User's Legacy ID
    val brazeExternalId: String, // The Braze analytics user id
  )
```

##### OnUserLogoutEvent {#onuserlogoutevent-1}

Triggered when the user logs out.

```kotlin
data class OnUserLogoutEvent(val username: String?)
```

##### OnLogAFEvent {#onlogafevent}

Logs an event to AppsFlyer

```kotlin
data class OnLogAFEvent(val eventName: String, val eventData: Map<String, Any>?)
```

##### OnGameStartedEvent {#ongamestartedevent}

Emitted when a game starts.

```kotlin
/**
 * Emitted when a game starts.
 *
 * @property tournamentInstanceEntryId Backend entry identifier for this tournament instance.
 * @property realCash Cash amount associated with the game 
 * @property userCashGamesPlayed Player's lifetime count of cash games at emit time.
 * @property isCashTournament Whether this start event represents a cash tournament.
 */
data class OnGameStartedEvent(
  val tournamentInstanceEntryId: String,
  val realCash: Double,
  val userCashGamesPlayed: Int,
  val isCashTournament: Boolean,
)
```

#### AppsFlyer Manager Handlers {#appsflyer-manager-handlers}

##### onInstallHandler {#oninstallhandler}

```kotlin
/** Install completed → log the AppsFlyer install event. */
fun onInstallHandler(event: OnInstallEvent) {
  logger.d(TAG, "OnInstallEvent: $event")
  // AF accepts null properties
  logAFEvent(AF_INSTALL, null)
}
```

##### onUserReadyHandler {#onuserreadyhandler-1}

```kotlin
/** User became available → set AF customer user ID (if changed). */
fun onUserReadyHandler(event: OnUserReadyEvent) {
  logger.d(TAG, "OnUserReadyEvent: $event")
  val user = event.data.user
  setUserId(user.userId)
}
```

##### onUserLogoutHandler {#onuserlogouthandler-1}

```kotlin
/** Logout → reset AF customer user ID (preserves current behavior; local cache is not cleared). */
fun onUserLogoutHandler(event: OnUserLogoutEvent) {
  logger.d(TAG, "OnUserLogoutEvent: $event")
  // Defensive: surface any SDK issues without crashing callers.
  runCatching { appsFlyerLib.setCustomerUserId(null) }
    .onFailure { e -> logger.e(TAG, "AppsFlyer setCustomerUserId(null) failed", e) }
}

```

##### onLogAFHandler {#onlogafhandler}

```kotlin
/** Passthrough for arbitrary AF events. */
fun onLogAFHandler(event: OnLogAFEvent) {
  logger.d(TAG, "OnLogAFEvent: $event")
  // logAFEvent handles blank names + nullable maps (AF supports null values).
  logAFEvent(eventName = event.eventName, eventKeyValues = event.eventData)
}
```

OnGameStartedHandler

```kotlin
/** Cash game started → log AppsFlyer event (cash-only). */
fun onGameStartedHandler(event: OnGameStartedEvent) {
  logger.d(TAG, "OnGameStartedEvent: $event")

  // Guard: only log for cash tournaments (preserves original behavior).
  if (!event.isCashTournament) return

  val props: Map<String, Any?> = mapOf(
    "cef" to event.realCash,
    "total_cash_games_played" to event.userCashGamesPlayed
  )

  logAFEvent(AF_CASH_TOURNAMENT, props)
}
```

#### AppsFlyer Manager Delegates {#appsflyer-manager-delegates}

##### setUserId {#setuserid}

```kotlin
/**
 * Updates the cached AppsFlyer customer user ID and forwards it to the SDK.
 *
 * Behavior :
 * - No-op if the incoming userId equals the cached value.
 * - When changed: updates local cache, logs, and calls AppsFlyer.setCustomerUserId(userId).
 */
private fun setUserId(userId: String) {
  // Avoid unnecessary SDK calls.
  if (this.userId == userId) return

  this.userId = userId
  logger.d(TAG, "Setting AppsFlyer customer user ID: $userId")

  // Defensive: prevent a stray SDK exception from bubbling.
  runCatching {
    appsFlyerLib.setCustomerUserId(userId)
  }.onFailure { e ->
    logger.e(TAG, "AppsFlyer setCustomerUserId failed for id=$userId", e)
  }
}

```

##### logAFEvent {#logafevent}

```kotlin
/**
 * Logs an AppsFlyer event if the name is non-blank.
 *
 * Behavior :
 * - Passes through nullable property values (AppsFlyer supports nulls).
 * - No-op if the event name is blank after trim.
 */
private fun logAFEvent(
  eventName: String,
  eventKeyValues: Map<String, Any?>? // allow nullable values per AF contract
) {
  val name = eventName.trim()
  if (name.isEmpty()) {
    logger.w(TAG, "Event name is blank; skipping AppsFlyer log.")
    return
  }

  logger.d(TAG, "Logging AppsFlyer event: $name, properties=$eventKeyValues")

  // Defensive: log if the SDK throws, but don't crash callers.
  runCatching {
    appsFlyerLib.logEvent(context, name, eventKeyValues)
  }.onFailure { e ->
    logger.e(TAG, "AppsFlyer logEvent failed for $name", e)
  }
}
```

### AppsFlyer Kotlin Processor Events {#appsflyer-kotlin-processor-events}

### AppsFlyer Manager Native Subscribers {#appsflyer-manager-native-subscribers}

### AppsFlyer Manager Native Handlers {#appsflyer-manager-native-handlers}

## Braze Manager {#braze-manager}

The Braze Manager coordinates marketing and communication events across the Web App and the Wrapper. Responsibilities are split: the Wrapper integrates the native Braze SDK and owns push notifications, and it exposes a pass-through logger so the Web Processor can forward Braze client events (eventName, eventData) with full device/context. The Web App handles Braze UI/UX features such as Content Cards (carousel) and in-app messages (IAMs). Both layers work together to keep customer identity, attributes, and subscription state aligned so targeting, delivery, and attribution remain accurate across the application.

### Native Braze SDKs {#native-braze-sdks}

#### [iOS Braze SDK](https://www.braze.com/docs/developer_guide/sdk_integration/?sdktab=swift) {#ios-braze-sdk}

#### [Android Braze SDK](https://www.braze.com/docs/developer_guide/sdk_integration) {#android-braze-sdk}

### Braze Manager Web Processor Events  {#braze-manager-web-processor-events}

### Braze Manager Subscribers {#braze-manager-subscribers}

#### Braze Manager Events {#braze-manager-events}

##### OnUserReadyEvent {#onuserreadyevent-1}

Indicates that the user is ready.

```kotlin
/** Indicates that the user is ready. */
data class OnUserReadyEvent(val data: DeviceEvent.UserReady)

data class UserReady(
    val preferences: Preferences, // The user's preferences for music, sound effects, and vibrations
    val user: User, // The user object containing userId
  )

  // Data class for the "preferences" field
 data class Preferences(
    val music: Boolean, // Whether music is enabled
    val soundEffects: Boolean, // Whether sound effects are enabled
    val vibrations: Boolean, // Whether vibrations are enabled
  )

  // Data class for the "user" field
  data class User(
    val userId: String, // The user's unique ID
    val userName: String, // the User's Unique username
    val legacyUserId: Int?, // the User's Legacy ID
    val brazeExternalId: String, // The Braze analytics user id
  )
```

##### OnUserLogoutEvent {#onuserlogoutevent-2}

Triggered when the user logs out.

```kotlin
data class OnUserLogoutEvent(val username: String?)
```

##### OnPushNotificationStateChangeEvent {#onpushnotificationstatechangeevent}

Indicates a change in the push notification allowed state.

```kotlin
data class OnPushNotificationStateChangeEvent(val isAllowed: Boolean)
```

#### Braze Manager Handlers {#braze-manager-handlers}

##### onUserReadyHandler {#onuserreadyhandler-2}

```kotlin
/** User is ready → switch Braze user (if ID is non-blank). */
fun onUserReadyEventHandler(event: OnUserReadyEvent) {
  val externalId = event.data.user.brazeExternalId.trim()
  logger.d(TAG, "OnUserReadyEvent: brazeExternalId='$externalId'")

  if (externalId.isEmpty()) {
    logger.e(TAG, "Braze external ID is blank; cannot change user.")
    return
  }

  // Preserves original behavior: delegate to existing helper.
  changeUser(externalId)
}
```

##### onUserLogoutHandler {#onuserlogouthandler-2}

```kotlin
/** Logout → reset Braze user and clear registered push token. */
fun onUserLogoutHandler(event: OnUserLogoutEvent) {
  logger.d(TAG, "OnUserLogoutEvent: $event")

  val braze = Braze.getInstance(context)
  // Defensive guard so SDK issues don't bubble up.
  runCatching {
    braze.changeUser("")
    braze.registeredPushToken = null
  }.onFailure { e ->
    logger.e(TAG, "Braze logout/reset failed", e)
  }
}
```

##### onPushNotificationStateChangeHandler {#onpushnotificationstatechangehandler}

```kotlin
/** Push notification permission changed → update Braze subscription. */
fun onPushNotificationChange(event: OnPushNotificationStateChangeEvent) {
  logger.d(TAG, "OnPushNotificationStateChangeEvent: allowed=${event.isAllowed}")
  updatePushSubscription(event.isAllowed)
}
```

#### Braze Manager Delegates {#braze-manager-delegates}

##### setBrazeInitializationArgs {#setbrazeinitializationargs}

```kotlin
/**
 * Initialize Braze on the responsive web (WebView) side.
 *
 * Validates inputs, logs succinctly, and forwards a minimal payload to the responsive layer.
 * If `apiKey` is blank, initialization is skipped (prevents noisy no-ops downstream).
 */
fun setBrazeInitializationArgs(args: InitializeBrazeArgs) {
  val apiKey = args.apiKey.trim()
  if (apiKey.isEmpty()) {
    logger.e(TAG, "initializeBraze: apiKey is blank; aborting initialization.")
    return
  }

  val payload = args.toPayloadMap() // {"apiKey": "...", ["deviceId": "..."]}

  logger.d(
    TAG,
    "initializeBraze: forwarding to responsive (deviceIdPresent=${args.deviceId != null})"
  )

  EventBus.post(OnInitializeBrazeRequested(payload))
}

/** Arguments to pass to initialize Braze on responsive web. */
data class InitializeBrazeArgs(
  val apiKey: String,
  val deviceId: String?
)

/** Stable payload shape for the responsive side (omit nulls). */
private fun InitializeBrazeArgs.toPayloadMap(): Map<String, Any> = buildMap {
  put("apiKey", apiKey.trim())
  deviceId?.let { put("deviceId", it) }
}

/** Local event for the responsive layer to consume. Replace with your project's event if it exists. */
data class OnInitializeBrazeRequested(val payload: Map<String, Any>)
```

##### setBrazeUser {#setbrazeuser}

```kotlin
/**
 * Switches the current Braze user and updates related state.
 *
 * Behavior :
 * - No-op if the incoming ID equals the cached `userId`.
 * - Posts `OnSetBrazeUserIDEvent` before updating the local cache (preserves ordering).
 * - Updates local `userId`, syncs Braze user, reapplies push subscription state,
 *   and sets the test-device attribute based on BuildConfig.DEBUG.
 */
private fun setBrazeUser(brazeExternalId: String) {
  // Avoid unnecessary SDK calls.
  if (this.userId == brazeExternalId) return

  // Notify listeners first.
  eventBus.post(OnSetBrazeUserIDEvent(brazeExternalId))

  // Update local cache.
  this.userId = brazeExternalId

  // Snapshot current push preference (used immediately after the user switch).
  val pushAllowed = preferencesManager.getIsPushNotificationAllowed()

  // Perform Braze calls with a single instance reference.
  val braze = Braze.getInstance(context)

  // Guard against SDK exceptions. 
  runCatching {
    braze.changeUser(brazeExternalId)
    updatePushSubscription(pushAllowed)
    // Set isTestDevice attribute on non-release builds (true on debug, false on release).
    setTestDevice(BuildConfig.DEBUG)
  }.onFailure { e ->
    logger.e(TAG, "Failed to change Braze user to '$brazeExternalId'", e)
  }
}
```

##### setPushSubscription {#setpushsubscription}

```kotlin
/**
 * Updates Braze push subscription state and flushes immediately.
 *
 * Behavior (unchanged):
 * - Sets the current user's push subscription to SUBSCRIBED/UNSUBSCRIBED.
 * - Always requests an immediate data flush (even if no current user).
 */
private fun updatePushSubscription(enabled: Boolean) {
  val type = if (enabled) {
    NotificationSubscriptionType.SUBSCRIBED
  } else {
    NotificationSubscriptionType.UNSUBSCRIBED
  }

  logger.d(TAG, "updatePushSubscription -> $type")

  val braze = Braze.getInstance(context)

  // Update subscription if a user is attached; otherwise log a warning.
  val user = braze.currentUser
  if (user != null) {
    runCatching { user.setPushNotificationSubscriptionType(type) }
      .onFailure { e -> logger.e(TAG, "Failed setting push subscription to $type", e) }
  } else {
    logger.w(TAG, "No Braze currentUser; push subscription not updated")
  }

  // Always flush to match original behavior.
  runCatching { braze.requestImmediateDataFlush() }
    .onFailure { e -> logger.e(TAG, "Braze immediate data flush failed", e) }
}
```

##### setTestDevice {#settestdevice}

```kotlin
/**
 * Marks the Braze user as a test device via a custom attribute.
 *
 * Behavior:
 * - Uses getCurrentUser(callback) and sets "isTestDevice" on the current user.
 * - No flush is requested here (matches original).
 */
private fun setTestDevice(isTestDevice: Boolean) {
  logger.d(TAG, "setTestDevice=$isTestDevice")

  val braze = Braze.getInstance(context)
  braze.getCurrentUser { user ->
    if (user == null) {
      logger.w(TAG, "Braze currentUser is null; cannot set '$TEST_DEVICE_ATTR'")
      return@getCurrentUser
    }

    runCatching {
      user.setCustomUserAttribute(TEST_DEVICE_ATTR, isTestDevice)
      logger.d(TAG, "Set Braze '$TEST_DEVICE_ATTR'=$isTestDevice")
    }.onFailure { e ->
      logger.e(TAG, "Failed to set '$TEST_DEVICE_ATTR'=$isTestDevice", e)
    }
  }
}

private companion object {
  private const val TEST_DEVICE_ATTR = "isTestDevice"
}
```

##### createUriActionFromUri {#createuriactionfromuri}

```kotlin
/**
 * Builds a Braze [UriAction] from a parsed [Uri]. No validation is performed.
 *
 * @param uri Deep-link URI.
 * @param extras Optional metadata.
 * @param openInWebView Open in WebView if true.
 * @param channel Source channel (push, in-app, etc.).
 * @return The constructed [UriAction].
 */
override fun createUriActionFromUri(
  uri: Uri,
  extras: Bundle?,
  openInWebView: Boolean,
  channel: Channel
): UriAction {
  logger.d(TAG, "createUriActionFromUri(uri=$uri, extras=$extras, openInWebView=$openInWebView, channel=$channel)")
  return UriAction(uri = uri, extras = extras, openInWebView = openInWebView, channel = channel)
}
```

##### createUriActionFromUrlString {#createuriactionfromurlstring}

```kotlin
/**
 * Builds a Braze [UriAction] from a raw URL string. Trims then delegates to [createUriActionFromUri].
 *
 * @param url Raw URL.
 * @param extras Optional metadata.
 * @param openInWebView Open in WebView if true.
 * @param channel Source channel.
 * @return The constructed [UriAction].
 */
override fun createUriActionFromUrlString(
  url: String,
  extras: Bundle?,
  openInWebView: Boolean,
  channel: Channel
): UriAction {
  val trimmed = url.trim()
  logger.d(TAG, "createUriActionFromUrlString(url=$trimmed, extras=$extras, openInWebView=$openInWebView, channel=$channel)")
  return createUriActionFromUri(Uri.parse(trimmed), extras, openInWebView, channel)
}
```

##### getIntentFlags {#getintentflags}

```kotlin

/**
 * Returns intent flags for Braze deep links (logged for traceability).
 *
 * @param purpose Reason flags are requested.
 */
override fun getIntentFlags(purpose: IBrazeDeeplinkHandler.IntentFlagPurpose): Int {
  logger.d(TAG, "getIntentFlags(purpose=$purpose) -> $activityIntentionsFlags")
  return activityIntentionsFlags
}
```

##### gotoNewsFeed {#gotonewsfeed}

```kotlin
/**
 * Executes the provided Braze [NewsfeedAction]. Errors are logged.
 *
 * @param context Application context.
 * @param newsfeedAction Action to execute.
 */
override fun gotoNewsFeed(context: Context, newsfeedAction: NewsfeedAction) {
  logger.d(TAG, "gotoNewsFeed()")
  runCatching { newsfeedAction.execute(context) }
    .onFailure { e -> logger.e(TAG, "gotoNewsFeed execution failed", e) }
}
```

##### gotoUri {#gotouri}

```kotlin
/**
 * Executes the provided [UriAction] (may open WebView or external browser). Errors are logged.
 *
 * @param context Application context.
 * @param uriAction Action to execute.
 */
override fun gotoUri(context: Context, uriAction: UriAction) {
  logger.d(TAG, "gotoUri(uri=${uriAction.uri})")
  runCatching { uriAction.execute(context) }
    .onFailure { e -> logger.e(TAG, "gotoUri execution failed for ${uriAction.uri}", e) }
}
```

### Braze Kotlin Processor Events {#braze-kotlin-processor-events}

### Braze Manager Native Subscribers {#braze-manager-native-subscribers}

### Braze Manager Native Handlers {#braze-manager-native-handlers}

# Technology {#technology}

[image1]: <data:image/png;base64,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>