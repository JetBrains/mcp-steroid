## Hand-off note: new "refresh-token-like" grant for long-lived credentials (Keycloak repo)

### Where the work lives
Grant types are pluggable providers. The SPI contract is in `server-spi-private/src/main/java/org/keycloak/protocol/oidc/grants/`: `OAuth2GrantTypeSpi.java`, `OAuth2GrantType.java`, `OAuth2GrantTypeFactory.java`.

Concrete grants live in `services/src/main/java/org/keycloak/protocol/oidc/grants/`, each as a **pair**: an implementation plus a factory (e.g. `RefreshTokenGrantType.java` + `RefreshTokenGrantTypeFactory.java`; same pattern for `AuthorizationCode`, `ClientCredentials`, `ResourceOwnerPasswordCredentials`, `TokenExchange`, `JWTAuthorization`, `Permission`, `PreAuthorizedCode`, and the sub-packaged `device/` and `ciba/` grants). There is also `OAuth2GrantTypeBase.java`, which the existing grants presumably extend — read it first.

The closest model to imitate is the refresh-token pair: the new operation is the same flow plus an up-front check that the presented credential is a long-lived one, rejecting interactive-session credentials with an `invalid_grant` protocol error (not a 500) *before* any token is issued.

### Dependencies between changes
1. New grant implementation + factory in the `grants` package, modelled on `RefreshTokenGrantType*`.
2. Provider registration: factories must be discoverable. The registration mechanism (`META-INF/services` entry or equivalent) was **not located** — confirm how `RefreshTokenGrantTypeFactory` is registered and mirror it exactly, or the grant will be silently absent.
3. Well-known/OIDC discovery metadata must advertise the new grant. The file(s) producing `grant_types_supported` were not pinned down in this record; locate them from that key and add the new identifier there.
4. Uniqueness invariant: every grant carries a distinct short identifier used to stamp issued tokens. Startup must fail if two grants share it. Whether such a check already exists is unknown — check the SPI/registry code path that loads grant factories and add the fail-fast validation if missing.

### Easy to miss
- The identifier is used in two roles (protocol `grant_type` value and token stamping); keep them consistent.
- The rejection must be ordered before token minting, so put it at the very start of the grant's processing.

### Verification
Follow `services/src/test/java/org/keycloak/protocol/oidc/grants/PreAuthorizedCodeGrantTypeTest.java` as the unit-test template. Cover: long-lived credential → new access token; interactive credential → `invalid_grant`; discovery document lists the grant; duplicate short identifier → server refuses to start.