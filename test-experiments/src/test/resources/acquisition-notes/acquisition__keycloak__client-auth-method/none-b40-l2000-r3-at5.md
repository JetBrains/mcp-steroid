## Hand-off note: self-signed-certificate client authentication

**What exists / what to imitate**

The only certificate-based client authenticator today is `services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java` (`PROVIDER_ID = "client-x509"`, attributes `x509.subjectdn`, `x509.casubjectdn`, `x509.allow.regex.pattern.comparison`). It is the template for the new provider: it obtains the presented chain via the `X509ClientCertificateLookup` SPI (`provider.getCertificateChain(context.getHttpRequest())`), resolves `client_id` from decoded form parameters, then query parameters, then the `client_id` session attribute (challenging with `invalid_client`/400 when absent), fails with `CLIENT_NOT_FOUND` / `CLIENT_DISABLED`, and calls `context.attempted()` when no certificate is presented so the rest of the flow continues. Only its subject-DN/CA matching part (which uses `CertificateValidator` from the sibling `authenticators/x509` package) should be replaced by an exact byte comparison against the certificate stored on the client. Note: the record does not establish where/under which key the client's uploaded certificate lives, nor the remainder of the file below the cert-presence check — read both before coding.

**Coupled changes**

1. New authenticator class in the same `authenticators/client` package; siblings include `AttestationBasedClientAuthenticator`, `FederatedJWTClientAuthenticator`.
2. Register it in `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory` — easy to miss; without this line the provider is invisible.
3. Add the method name constant beside `OIDCLoginProtocol.TLS_CLIENT_AUTH = "tls_client_auth"` (`services/.../protocol/oidc/OIDCLoginProtocol.java:129`) and follow every existing use of that constant: `core/.../representations/oidc/OIDCClientRepresentation.java`, `services/.../clientregistration/oidc/DescriptionConverter.java` (dynamic registration), `OIDCAdvancedConfigWrapper`, and the discovery/well-known metadata (its provider class was not located).
4. Security profiles / client policies: `DefaultClientValidationProvider` and `clientpolicy/executor/TlsClientAuthCASubjectDNExecutor(Factory)` special-case `X509ClientAuthenticator`; check whether the new id must be tolerated there too.

**Verification**

Existing coverage to extend: `tests/base/.../client/AbstractMutualTLSClientTest.java`, `testsuite/.../oidc/AbstractWellKnownProviderTest.java` (plus `servers/.../wellknown/oidc-well-known-config-override.json`), `client/OIDCClientRegistrationTest.java`, and the FAPI / `OAuth2_1ConfidentialClientTest` / `client/policies/*` suites. Cover: matching cert authenticates; any different cert, including a re-issued one with identical subject DN and key, is rejected; no trust store consulted.