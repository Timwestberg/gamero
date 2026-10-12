# Desktop browser profile — FOR-80

A normal Mac close/relaunch retained the Keychain host and completed task but
returned the browser to sign-in. Reconnect currently requires re-authentication;
seamless browser-session restoration has not yet passed Mac validation.

## Verified cause in the installed dependency

Gamero sets an absolute `CefSettings.cache_path` for its global profile. However,
compose-webview-multiplatform desktop 2.0.3's default URL factory obtains
`WebViewFactoryParam.requestContext`, whose `createModifiedRequestContext` calls
`CefRequestContext.createContext(handler)`. Inspection of the installed JAR's
bytecode verifies that call chain. JCEF's native implementation creates that
context using empty settings, independently of the configured global profile:
https://github.com/JetBrains/jcef/blob/dev/native/CefRequestContext_N.cpp

Identity already issues access/refresh/CSRF cookies with Max-Age. The
`persist_session_cookies` option applies to cookies without expiry; turning it on
would change policy and would not correct the selected request context:
https://github.com/JetBrains/jcef/blob/dev/java/org/cef/CefSettings.java

## Change and limits

Only the desktop host opts into a URL browser factory using the supported global
request context. Rendering and transparency settings are preserved. Other callers
retain the library's default factory. The desktop uses no custom user-agent or
request-context override. The factory checks the actual browser context and emits
`GAMERO_BROWSER_CONTEXT: global=true`, without URLs or cookie values.

Cookie lifetimes, Secure/HttpOnly/SameSite, server revocation, OS-protected browser
storage and provider authentication remain unchanged. Session-only cookies remain
session-only. No cookie values are read, exported, migrated or manually stored.
An existing private-context login cannot be migrated; one normal sign-in after
switching profiles is expected before testing another normal close/relaunch.

## Validation

JVM compilation/JAR and regression-suite results are recorded on the PR. Source
review covers the installed factory call chain and context selection. No mocked
unit test can establish native cookie persistence. Coordinate a later Mac check:
sign in normally, confirm the global-context marker, close cleanly, relaunch with
the same profile and URL, and verify account/host/completed-task restoration with
no task rerun. Also verify explicit sign-out stays signed out after restart.
Do not interrupt the current Mac acceptance run or change its profile now.

Leave the PR draft/open pending Mac validation. No merge or deployment.
