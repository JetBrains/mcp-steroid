# Task candidates for the repository-understanding experiment

Five candidates were specified against the Keycloak tree at the pinned base commit
`60c4d5e9321ff5462a772ceb896f8cb2e639e04b` (the same commit the semantic-ripple family uses, so the
container, the JDK, the reactor install and the scoped grading are already proven there).

Every candidate had to satisfy four criteria: the statement must not leak localization; a precedent must
exist in the tree; the gold change must span several conceptually different roles; and the difficulty
must sit in the band *baseline rarely succeeds, note-guided sometimes succeeds*. The fourth criterion is
what decided the field.

## The shell audit that rejected the first three

For each candidate, the commands a shell-only agent would plausibly issue from the statement ALONE were
executed against the clone, in order, and the point at which the agent holds every gold file was
recorded.

| candidate | module | roles | after how many commands the agent holds all gold files | verdict |
|:---|:---|--:|:---|:---|
| `notPalindrome` password rule | `server-spi-private` | 3 | **3** — `find . -name '*PasswordPolicyProvider*'` returns both siblings, the `META-INF/services` file and an existing unit test in one screen | baseline solves it **usually** — rejected |
| `starts-with-letter` validator | `server-spi-private` | 3 | **2** — `find . -name '*Validator*'` puts `META-INF/services/...ValidatorFactory` and `BuiltinValidators.java` on the first two lines | baseline **usually/sometimes** — rejected |
| `andotp` OTP application | `services` | 2 | 2 | thin (one class + one line), baseline usually — rejected |

The lesson generalises: **a task whose second integration point sits in the same directory as its first
is not an understanding task.** Any `find` over the family name reveals it.

Required-action providers were investigated and dropped for a different reason: their second
integration point does exist (`DefaultRequiredActions`, `UserModel.RequiredAction`), but the provider
contract cannot be exercised without faking `RealmModel`, and the `services` module has no Mockito.

## The two that survived

### D1 — built-in OIDC mapper `email_domain` (SELECTED)

*Module* `services` (`keycloak-services`). *Roles* behaviour, config properties, marker interfaces,
`META-INF/services` registration, **and the built-in mapper map in
`OIDCLoginProtocolFactory.initBuiltIns()`**.

What makes it the right case is that last role. "Available out of the box in a fresh realm" is not
implemented where mappers live; it is implemented in the protocol factory, in another package, by a
mechanism that has nothing to do with `ServiceLoader`. No word of the statement greps onto it
(`email_domain` 0 hits, `oidc-email-domain-mapper` 0 hits, "out of the box" and "freshly created realm"
are not code words), and the default partial solution — copy `HardcodedClaim`, add the META-INF line —
fails the oracle on exactly that point. That is a sentence a strong agent can put in a note, and the
whole experiment is whether it does.

The oracle is a plain JUnit test in the `org.keycloak.protocol.oidc` package that resolves the mapper
through `ServiceLoader` and through the built-in map, transforms an `IDToken` with no session, and
checks the marker interfaces — no server, no database, no network, no Mockito.

### D2 — client-policy condition on the processing phase (runner-up)

*Module* `services`. *Roles* provider, nested configuration representation bound by Jackson under
kebab-case keys, factory metadata, `META-INF/services`. The point an agent misses is
`addCommonConfigProperties` (the `is-negative-logic` switch every condition must offer).

Not selected for two reasons: `grep -ril "client policy"` returns 37 files and lands in the right
package immediately, so localization is easier than in D1; and the statement would have to name the
internal phase constants to make the oracle unambiguous, which weakens criterion A.

## Open risks on the selected case

- The mapper could be implemented against `userSession.getUser().getEmail()` instead of the token being
  issued. The statement says "the e-mail address carried by the token that is being issued" verbatim to
  close that door, and the oracle transforms a token with no session at all.
- `mvn test -pl :keycloak-services` wall time is estimated at 3–6 minutes from the module's 95 test
  classes; it has not been measured. The calibration runs will measure it, and it is the only number in
  the cost model that could move materially.
