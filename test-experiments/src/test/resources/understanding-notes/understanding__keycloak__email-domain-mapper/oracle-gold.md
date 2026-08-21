A claim like this is produced by a *protocol mapper*. All the OIDC ones live in
`services/src/main/java/org/keycloak/protocol/oidc/mappers/`. Read `HardcodedClaim.java` first — it is
the closest precedent and its shape is the whole answer: ONE class that is simultaneously the provider
and its own factory, extending `AbstractOIDCProtocolMapper` and implementing the marker interfaces
`OIDCIDTokenMapper`, `OIDCAccessTokenMapper`, `UserInfoTokenMapper` and `TokenIntrospectionTokenMapper`.
Those four interfaces are not decoration: the base class only calls you for a token whose marker you
implement, so leaving one out silently disables the mapper for that token. Implement all four.

What to write:

- `getId()` returns `oidc-email-domain-mapper`; `getProtocol()` returns `OIDCLoginProtocol.LOGIN_PROTOCOL`;
  `getDisplayCategory()` returns `AbstractOIDCProtocolMapper.TOKEN_MAPPER_CATEGORY`; `getDisplayType()`
  is free text for the admin console.
- `getConfigProperties()` returns a list built with `OIDCAttributeMapperHelper`: `addTokenClaimNameConfig`,
  `addJsonTypeConfig`, then `addIncludeInTokensConfig(list, YourMapperClass.class)`. That call is what
  creates the four administrator switches (`id.token.claim`, `access.token.claim`,
  `userinfo.token.claim`, `introspection.token.claim`); do not invent your own booleans, the base class
  reads exactly those keys.
- Override `setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
  KeycloakSession keycloakSession, ClientSessionContext ctx)`. Take the address from
  `token.getEmail()` — the token being issued, NOT from the user or the session; the graded test passes
  null for the session parameters. If the e-mail is null or has no `@`, return without touching the
  token. Otherwise write the domain with `OIDCAttributeMapperHelper.mapClaim(token, mappingModel, value)`,
  which honours the configured claim name and JSON type.
- Follow the precedent and add a `public static ProtocolMapperModel create(...)` helper; it is how the
  built-in registration below builds its model.

Then two registrations, and this is where the task is really lost — they are different mechanisms and
doing only the first looks complete:

1. **Discovery.** Append the fully-qualified class name to
   `services/src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper`. This is what
   makes the mapper resolvable by its provider id at runtime.
2. **"Out of the box".** Being on the service list does NOT put a mapper into a new realm. That is done
   in `services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocolFactory.java`, method
   `initBuiltIns()`, which fills a `builtins` map of `ProtocolMapperModel`s that `getBuiltinMappers()`
   hands out and that the default client scopes are built from. Add your model there, next to the
   existing `builtins.put(...)` lines, using your `create(...)` helper with all four include-in-token
   flags true. Note the map is keyed by display name, not by provider id.

Invariants and traps:

- Do not modify any existing mapper, the base class, or any existing entry of the service file — only
  append.
- `initBuiltIns()` is package-private and calls `Profile.isFeatureEnabled(...)`; keep your addition
  unconditional so it does not depend on a feature flag.
- Do not lower-case or otherwise normalise the domain, and do not add the claim as an empty string when
  the part after `@` is empty.
- Keep the class in the `org.keycloak.protocol.oidc.mappers` package. The service file's entry must
  match the real package exactly or the mapper will not be found.

Verification: `./mvnw test -pl services` (artifactId `keycloak-services`). Existing unit tests in that
module — `protocol/oidc/mappers/OIDCAttributeMapperHelperTest` and
`protocol/docker/mapper/AllowAllDockerProtocolMapperTest` — are the right pattern: plain JUnit 4, no
server, null sessions. Compile first: a mistake inside `OIDCLoginProtocolFactory` breaks the whole
module, and the module must compile for anything to be graded.
