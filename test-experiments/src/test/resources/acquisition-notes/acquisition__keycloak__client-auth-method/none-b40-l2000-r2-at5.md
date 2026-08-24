**Hand-off note — self-signed-certificate client authentication (Keycloak, Maven multi-module)**

**What to imitate**

`services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java` is the model. It extends `AbstractClientAuthenticator`, has `PROVIDER_ID = "client-x509"`, and:
- obtains the presented chain from the `X509ClientCertificateLookup` provider (`context.getSession().getProvider(...)`, `provider.getCertificateChain(context.getHttpRequest())`), failing softly (`context.attempted()`) on `GeneralSecurityException`;
- resolves `client_id` from decoded form params, then query params, then the session attribute `"client_id"`; challenges with `ClientAuthUtil.errorResponse(400, "invalid_client", ...)` when absent; `CLIENT_NOT_FOUND` / `CLIENT_DISABLED` failures; sets event + `context.setClient(client)`;
- declares config attributes `x509.subjectdn`, `x509.casubjectdn`, `x509.allow.regex.pattern.comparison` and imports `CertificateValidator` — the trust-chain/subject-DN logic you must *not* reproduce (only the first ~125 lines were read; re-read the rest before copying);
- around line 262 contributes `OIDCLoginProtocol.TLS_CLIENT_AUTH` to a list of supported protocol authentication methods — this is how the method reaches the realm discovery document.

Sibling authenticators `AttestationBasedClientAuthenticator`, `FederatedJWTClientAuthenticator` show recently-added providers in the same package.

**Change dependencies**

1. New authenticator class in that package; add a new method-name constant next to `TLS_CLIENT_AUTH` in `services/.../protocol/oidc/OIDCLoginProtocol.java` (line 129) and return it from the new authenticator's supported-methods method.
2. Register the class in `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory` — easily forgotten; without it the provider silently does not exist.
3. Client-policy executors enumerate acceptable authenticators: `ClientIdMetadataDocumentExecutor` (line 108) lists `TLS_CLIENT_AUTH`; also see `TlsClientAuthCASubjectDNExecutorFactory`. Which executors implement the "security profiles" that must accept the new method is **not established** — audit all executors referencing the authenticator/method constants.
4. Dynamic registration: `core/.../representations/oidc/OIDCClientRepresentation` (`tls_client_auth_subject_dn`), `services/.../clientregistration/oidc/DescriptionConverter` (line 189) and `OIDCAdvancedConfigWrapper.setTlsClientAuthSubjectDn` are the existing mapping path to inspect.

**Unknown**: where a client's uploaded certificate is stored/read (admin UI page is `js/apps/admin-ui/src/clients/credentials/X509.tsx`); resolve this before writing the byte-for-byte comparison.

**Verify**: existing X509 coverage lives in `testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/x509/` (see `AbstractX509AuthenticationTest`) and newer tests under `tests/base`. Cover: exact-cert match succeeds; different cert fails; re-issued cert with identical subject DN and key fails.