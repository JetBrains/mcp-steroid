Imitate `X509ClientAuthenticator` (same package,
`services/src/main/java/org/keycloak/authentication/authenticators/client/`): a single class extending
`AbstractClientAuthenticator`, which is both the authenticator and its own factory — there is no
separate factory class in this SPI. Call it after the RFC term for the method; `PROVIDER_ID` is a
provider id such as `client-self-signed-x509`.

`authenticateClient(ClientAuthenticationFlowContext)` does, in order:

- `context.getSession().getProvider(X509ClientCertificateLookup.class)`, then
  `provider.getCertificateChain(context.getHttpRequest())`. A null provider or an empty chain means
  `context.attempted()` — this authenticator must never fail a flow that is simply not using mutual
  TLS;
- resolve `client_id` from the decoded form parameters, then the query parameters, then the session
  attribute (the neighbour does the same three-step lookup), `realm.getClientByClientId`,
  `context.setClient`, and the disabled/unknown-client failures;
- read the client's registered certificate from `client.getAttribute(JWTClientAuthenticator.CERTIFICATE_ATTR)`
  and parse it with `PemUtils.decodeCertificate`;
- compare the **leaf** certificate presented in the handshake with the registered one **byte for
  byte** (SHA-256 thumbprint over `getEncoded()`), and `context.success()` only on an exact match.

That comparison is the trap. The neighbour you are copying authenticates against a trust anchor and
then matches an identity expression — `ATTR_CA_SUBJECT_DN`, subject DN, thumbprint mapping, all
configurable. RFC 8705 §2.2 is the opposite: no certificate authority is consulted at all and the
certificate itself is the credential. An implementation that validates a chain or matches a subject DN
looks right, passes a happy-path test, and is a different authentication method.

Provider id is NOT the protocol method string. Override
`getProtocolAuthenticatorMethods(String loginProtocol)` to return `self_signed_tls_client_auth` for
`OIDCLoginProtocol.LOGIN_PROTOCOL`, and add that constant beside `TLS_CLIENT_AUTH` in
`services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java`. `OIDCWellKnownProvider`
builds `token_endpoint_auth_methods_supported` by streaming the registered factories and collecting
exactly that set, so an authenticator that skips this override works but is invisible in discovery and
cannot be selected by an OIDC method name.

Two registrations, and the change is incomplete without either:

1. append the class to
   `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory`;
2. add the provider id to `allowed-client-authenticators` of the `secure-client-authenticator`
   executor in `services/src/main/resources/keycloak-default-client-profiles.json` — the shipped
   high-security profiles carry an explicit allow-list, so without this entry a client using the new
   method is rejected under FAPI/OAuth-2.1 even though the SPI works.

Verify with a plain JUnit test in the `services` module; no server, no database, no Mockito.
