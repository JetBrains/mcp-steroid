## Hand-off note: dedicated refresh grant for long-lived (offline) credentials

### Where the relevant code lives
This is the Keycloak monorepo (Maven, Java 17, modules `core`, `server-spi-private`, `services`, `tests`, `test-framework`, …).

Grant types are a provider SPI, not a switch statement. The contract is in `server-spi-private/src/main/java/org/keycloak/protocol/oidc/grants/`:
- `OAuth2GrantType` — `Provider` interface: `getEventType()`, `getTokenParameterNames()`, `Response process(Context)`, optional `preProcess(...)` (client policies), optional `isTokenAllowed(...)` for endpoint restriction. `Context` is a static nested class carrying session, realm, client, form params, `EventBuilder event`, `Cors`, `tokenManager`, `grantType`, `protocol`.
- `OAuth2GrantTypeFactory` — `ProviderFactory<OAuth2GrantType>` plus `String getShortcut()`, documented as "usually like 3-letters shortcut … useful in the tokens when the amount of characters should be limited … Shortcut should be unique across grants." That javadoc is the invariant the task asks you to actually enforce; the record does not show where (if anywhere) uniqueness is currently checked — inspect `OAuth2GrantTypeSpi` and the provider/factory bootstrap for the right place to fail fast.
- Implement the new grant by imitating the existing grant implementations in this package (their bodies were not examined — read the existing refresh-token grant first and derive from it, so the happy path stays byte-identical for long-lived credentials).

### Change set and dependencies
1. New grant provider + factory (new `grant_type` value and a fresh, unique shortcut). Reject an ordinary interactive-session refresh token **before** any token is minted, returning an OAuth "invalid grant" protocol error, not a 500. Error constants live in `core/.../OAuthErrorException.java`; grant-type/parameter names in `core/.../OAuth2Constants.java`.
2. Register the factory for the SPI (service-loader registration alongside the other grants).
3. Advertise it in discovery: `services/.../protocol/oidc/OIDCWellKnownProvider.java`, backed by `core/.../protocol/oidc/representations/OIDCConfigurationRepresentation.java`.
4. Startup validation for duplicate shortcuts.

### Easy to miss
- Event/audit separation is part of the point: pick the right `getEventType()`, and note `server-spi-private/.../events/Details.java` records grant type.
- `services/.../managers/GrantTypeEndpointRestrictionValidator.java` may need to know the new grant.

### Verification
Add tests under `tests/base/src/test/java/org/keycloak/tests/oauth/` next to `OfflineTokenBasicFlowTest`; `test-framework/oauth` and `test-framework/core`'s `EventAssertion` support grant/event assertions. Cover: offline credential → new access token; interactive refresh token → invalid_grant; discovery document lists the grant; duplicate shortcut prevents startup.