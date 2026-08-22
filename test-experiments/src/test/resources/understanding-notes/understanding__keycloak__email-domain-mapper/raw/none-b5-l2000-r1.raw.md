Standard Keycloak OIDC mapper addition. Closest template (verified): `services/src/main/java/org/keycloak/protocol/oidc/mappers/UserSessionNoteMapper.java`. Imitate it.

1) New class in that same package, e.g. `EmailDomainMapper extends AbstractOIDCProtocolMapper implements OIDCIDTokenMapper, OIDCAccessTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper`; `PROVIDER_ID = "oidc-email-domain-mapper"`. Implement getId/getDisplayType/getHelpText; `getDisplayCategory()` → `ProtocolMapperUtils.TOKEN_MAPPER_CATEGORY`; static `List<ProviderConfigProperty>` built via `OIDCAttributeMapperHelper`. Prefer the helper that adds only the four include-in switches (`addIncludeInTokensConfig(list, MyClass.class)`); `addAttributeConfig` also adds claim-name/JSON-type config, which you don't want since the claim name is fixed. Verify the exact helper names.

2) Override `protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession, KeycloakSession session, ClientSessionContext ctx)` (the 5-arg overload, AbstractOIDCProtocolMapper ~line 156). Its transformIDToken/transformAccessToken/transformUserInfoToken/transformIntrospectionToken each test the matching `OIDCAttributeMapperHelper.includeInX(mappingModel)` before calling setClaim, so one override honours all four switches for free. Read `token.getEmail()` (AccessToken extends IDToken), split on last/first `@`, guard null/blank/no-`@`/empty domain, else `token.getOtherClaims().put("email_domain", domain)`.

3) Register the provider: add the FQCN line to `services/src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper`. Without it the id won't resolve. (Path inferred, not read.)

4) "Ships with the server": `services/.../protocol/oidc/OIDCLoginProtocolFactory.java` populates a `builtins` map (plus a separate default set feeding default client scopes / `createDefaultClientScopes`). Add an entry with a fixed name. I could not read this file — confirm field/method names and whether a `defaultBuiltins`/client-scope registration is also needed for your interpretation of the requirement.

5) Likely gotcha (unverified): email is itself written by a builtin UserPropertyMapper, so yours must run afterwards — check `ProtocolMapper.getPriority()` and priority constants in `ProtocolMapperUtils`, and override with a value above default.

6) Also unverified but likely: tests asserting mapper counts/sets (testsuite ProtocolMappersTest, client-scope and server-info tests, exported-realm JSON fixtures) may need updating.

Do not touch IDToken, AbstractOIDCProtocolMapper, or existing mappers.
