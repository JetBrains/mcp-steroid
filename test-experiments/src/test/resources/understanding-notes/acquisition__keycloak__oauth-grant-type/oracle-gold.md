Imitate `RefreshTokenGrantType` and `RefreshTokenGrantTypeFactory` (same package,
`services/src/main/java/org/keycloak/protocol/oidc/grants/`): the token endpoint resolves an
`OAuth2GrantType` provider by the `grant_type` form parameter, so a new grant is an
`OAuth2GrantType` implementation plus its `OAuth2GrantTypeFactory`.

Extend the refresh-token grant rather than reimplementing token issuance. The subclass:

- declares the grant URI it answers to, e.g.
  `urn:ietf:params:oauth:grant-type:offline-refresh`, returned by the factory's `getId()`;
- overrides `process(Context context)`, calls `setContext(context)` FIRST (the inherited fields —
  `formParams`, `event`, `cors` — are populated there and are null before it),
- reads `formParams.getFirst(OAuth2Constants.REFRESH_TOKEN)` and rejects a missing one with
  `CorsErrorResponseException(cors, OAuthErrorException.INVALID_REQUEST, …, BAD_REQUEST)`;
- rejects a token that is not an offline token — `TokenUtil.isOfflineToken(refreshToken)`, with the
  `JWSInputException` branch turned into `INVALID_GRANT` — **before** delegating, recording
  `event.detail(Details.REASON, …)` and `event.error(Errors.INVALID_TOKEN)`;
- only then `return super.process(context)`, so the whole issuance path stays shared.

The invariant lives in another subsystem and the neighbour says nothing about it. Every
`OAuth2GrantTypeFactory` also returns a **two-character `getShortcut()`**, and
`services/src/main/java/org/keycloak/protocol/oidc/encode/DefaultTokenContextEncoderProviderFactory#postInit`
builds id→shortcut and shortcut→id maps over all registered factories and throws
`IllegalStateException` when the two maps differ in size, i.e. when any id or shortcut repeats. The
shipped codes are `ac`, `cc`, `pg`, `rt`, `ro`, `te`, `ci`, `dg`, `pc`, `ag`; `or` is free. Reusing
the neighbour's `RefreshTokenGrantTypeFactory.GRANT_SHORTCUT` (`"rt"`) compiles, passes every test in
the grants package, and prevents the server from starting.

Registration: append the factory to
`services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory`.
That one file is what makes the grant dispatchable AND what puts it into `grant_types_supported` —
`OIDCWellKnownProvider` composes that list by streaming the registered grant factories, so there is no
second place to declare the grant in discovery, and equally no way to appear in discovery without
being registered.

Verify with a plain JUnit test in the `services` module; no server, no database, no Mockito.
