**Hand-off note — new dedicated token-renewal grant type**

**Architecture to imitate**
Token-endpoint grants are pluggable providers under the SPI `oauth2-grant-type`:
- `server-spi-private/.../protocol/oidc/grants/`: `OAuth2GrantType` (Provider), `OAuth2GrantTypeFactory` (ProviderFactory), `OAuth2GrantTypeSpi` (internal SPI, name `oauth2-grant-type`).
- Implementations live in `services/.../protocol/oidc/grants/`, always as a pair `XGrantType` + `XGrantTypeFactory`. Confirmed examples: `AuthorizationCodeGrantType(+Factory)`, `ClientCredentialsGrantType(+Factory)`, `ciba/CibaGrantType(+Factory)`. The existing refresh/renewal grant should be in the same package (the package listing was only partly read) — find it and mirror it closely; the new operation must reuse its token-issuing path so a valid long-lived credential yields the same response as before.

**Interface contract you must satisfy**
`OAuth2GrantType`: `getEventType()`, `getTokenParameterNames()`, `process(Context)` returning a JAX-RS `Response`; optional `preProcess(session, formParams)`, `getSupportedMultivaluedRequestParameters()`, `isTokenAllowed(session, token)` (defaults to true). `Context` carries session/realm/client/clientConfig/formParams/event/cors/tokenManager/grantType/protocol — read it fully before writing `process`.
`OAuth2GrantTypeFactory.getShortcut()`: “3-letter shortcut … must be unique across grants”, used inside issued tokens. Pick a new one.

**Work items and dependencies**
1. New `GrantType`+`Factory` pair in the services grants package; grant-name constant most plausibly belongs with the others in `core/.../OAuth2Constants.java`.
2. In `process`, reject an interactive-session credential *before* minting anything, as a protocol `invalid_grant` error (see `core/.../OAuthErrorException.java`, `services/.../utils/OAuth2Error.java`) — not a server error.
3. Publish in server metadata: entry point is `services/.../protocol/oauth2/OAuth2WellKnownProviderFactory.java`; the actual supported-grants list was not inspected.
4. Enforce shortcut uniqueness at startup (fail fast on duplicates). The place where grant factories are collected/initialised was **not located** in this record — locate it first, as items 1 and 4 both depend on it.
5. Factory registration mechanism (provider service file) was not established.

**Easy to miss:** `services/.../managers/GrantTypeEndpointRestrictionValidator.java` and `isTokenAllowed` govern which grants/tokens are accepted where — check whether the new grant needs entries there.

**Verification:** no tests were examined; test locations unknown.