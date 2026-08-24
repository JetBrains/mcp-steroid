## Hand-off note

### State of the work
Only locations were established; no file contents were read. Treat every statement about what a file contains as unverified.

### What to imitate, and where
- The existing certificate-based authenticator to model the new one on:
  `services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java`.
  This is the one that today demands a CA chain and a configured subject-DN match; the new "self-signed certificate" authenticator belongs in the same package as a sibling, not as a modification of it (the old behaviour must stay).
- Shared base class: `AbstractClientAuthenticator.java` in that same package. Other siblings worth reading for package conventions: `JWTClientAuthenticator.java`, `FederatedJWTClientAuthenticator.java`, and `ClientAuthUtil.java`.
- Likely helpers, not yet inspected: `services/src/main/java/org/keycloak/services/util/CertificateInfoHelper.java` (client credential/certificate configuration) and `common/src/main/java/org/keycloak/common/util/PemUtils.java` for reading the stored certificate.

### Change dependencies
1. New authenticator/factory class in the client-authenticators package.
2. Register the factory in `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory`. Nothing else can pick it up until this is done.
3. The discovery document's list of token-endpoint auth methods and the built-in security-profile checks that accept certificate-based clients were **not located** — you must find them and decide whether they enumerate registered factories automatically or hard-code names. Do not assume it is automatic.

### Easy to miss
- There are build-output duplicates of the services file under `services/target/classes/...`; edit only the `src/main/resources` copy.
- A second, separate `ClientAuthenticatorFactory` service file exists under `testsuite/integration-arquillian/servers/auth-server/services/testsuite-providers/` (home of test-only authenticators such as `ClientIdRequiredJWTClientAuthenticator`). Production authenticators do not go there.
- PEM/crypto handling has several providers (`crypto/default`, `crypto/fips1402`, `crypto/elytron`), each with its own PemUtils test. Byte-for-byte comparison logic must behave identically across them.

### Verification
Nothing about existing tests for X509 client auth was established. Locate the tests around the existing X509 authenticator first, and cover: identical certificate accepted; different certificate rejected; re-issued certificate with same subject and same key rejected; no trust store consulted.