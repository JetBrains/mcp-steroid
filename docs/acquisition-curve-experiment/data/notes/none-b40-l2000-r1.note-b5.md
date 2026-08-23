**Hand-off note: enforce "no refresh token from client credentials grant" under the `strict` security profile**

**Where the feature lives**
- SPI: `server-spi-private/src/main/java/org/keycloak/securityprofile/` — `SecurityProfileSpi`, `SecurityProfileProvider`, `SecurityProfileProviderFactory`.
- Implementation: `services/src/main/java/org/keycloak/services/securityprofile/` — `DefaultSecurityProfileProvider`, `DefaultSecurityProfileProviderFactory` (unit-tested by `services/src/test/java/org/keycloak/services/securityprofile/DefaultSecurityProfileProverFactoryTest.java`, note the misspelling).
- Config representation: `core/src/main/java/org/keycloak/representations/idm/SecurityProfileConfiguration.java`.
- Shipped profiles: `services/src/main/resources/{none,strict,lax}-security-profile.json`. Each is tiny and only names a profile/policy set, e.g. `strict` → `client-profiles: keycloak-default-client-profiles`, `client-policies: keycloak-strict-client-policies`; `none` and `lax` are the same shape. Those names are *strings only* — no file in the repo is named after them, so the actual profiles/policies are assembled in code. `services/src/main/java/org/keycloak/services/clientpolicy/ClientPoliciesUtil.java` is the one place outside the `securityprofile` package that mentions security profiles and is the most likely home; confirm before editing.

**Shape of the change (dependency order)**
1. Add the enforcement itself in the client-policy layer (an executor is the natural fit, since client policies are what `strict` selects). Enforcement must cover client *create* and *update*, and must reject an update that omits the flag when the stored client already has it on — i.e. read the effective value, not just the request. Restrict it to confidential OIDC clients; a create that omits the flag must end with it off.
2. Wire it into the `keycloak-strict-client-policies` set so it is active out of the box; leave `none` (and `lax`) untouched so a profile-less server is unchanged.
3. Because it sits in client policies, both the admin REST path and dynamic client registration should be covered by one hook — verify both, don't assume.

**Verification**
- Imitate `tests/base/src/test/java/org/keycloak/tests/securityprofile/LaxSecurityProfileTest.java` (~260 lines, currently the only test there) for a strict-profile counterpart, and register it in `tests/base/src/test/java/org/keycloak/tests/suites/Base3TestSuite.java` as `LaxSecurityProfileTest` is.
- Also re-run the legacy `testsuite/integration-arquillian/.../client/FAPI2Test.java` and `FAPI2DPoPTest.java`, which exercise security profiles and could regress.