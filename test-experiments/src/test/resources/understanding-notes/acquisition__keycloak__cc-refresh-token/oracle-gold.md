Imitate `ConsentRequiredExecutor` (same package,
`services/src/main/java/org/keycloak/services/clientpolicy/executor/`): a new executor plus its
factory, `PROVIDER_ID = "reject-client-credentials-refresh-token"`, with an `auto-configure` boolean
in a `ClientPolicyExecutorConfigurationRepresentation` subclass.

The behaviour is the client attribute `client_credentials.use_refresh_token`
(`OIDCConfigAttributes.USE_REFRESH_TOKEN_FOR_CLIENT_CREDENTIALS_GRANT`) — a String in
`ClientRepresentation#getAttributes()` and in `ClientModel#getAttribute(String)`.

`executeOnEvent(ClientPolicyContext)` switches on `context.getEvent()`:

- `REGISTER`: cast to `ClientCRUDContext`. If `auto-configure` is on and the proposed representation
  carries no value for the key, put `"false"` (the attributes map is null by default — create it).
  Then throw `new ClientPolicyException(Errors.INVALID_REGISTRATION, …)` when the proposed value is
  `"true"`.
- `UPDATE`: no auto-configure. The effective value is the proposed representation's attribute **only
  when its map actually contains the key**; otherwise it is
  `context.getTargetClient().getAttribute(…)`, with a null target meaning "no stored value". Throw
  when the effective value is `"true"`. This is the trap: an update that never mentions the setting
  must still be refused for a client that already has it on, and the executor whose shape you are
  copying returns early in that case.
- any other event: return.

Both the admin REST API and dynamic client registration raise these same two events, so one executor
covers both entry points and no REST resource needs touching.

Two registrations, and the change is incomplete without either:

1. append the factory to
   `services/src/main/resources/META-INF/services/org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory`;
2. add `{"executor": "reject-client-credentials-refresh-token", "configuration": {"auto-configure":
   true}}` to the profile `oauth-2-1-for-confidential-client` in
   `services/src/main/resources/keycloak-default-client-profiles.json`, and to no other profile —
   that is the one the strict security profile's enabled confidential-client policy binds
   (`strict-security-profile.json` → `keycloak-strict-client-policies.json`).

Verify with a plain JUnit test in the `services` module, beside
`SecureRedirectUrisEnforcerExecutorTest`; no server, no database.
