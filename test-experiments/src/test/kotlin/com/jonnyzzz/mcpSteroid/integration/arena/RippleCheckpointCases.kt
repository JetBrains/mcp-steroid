/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One case the checkpoint harness records and probes: where its states live, what it is a case OF, and
 * which arm tokens address them.
 *
 * [resourceDir] is the directory under `src/test/resources/ripple-checkpoints`. Short rather than the
 * full [instanceId]: the path appears in every probe build's configuration and in the README an
 * operator follows when copying patches out of a capture artifact.
 *
 * [arms] holds the TOKENS a probe cell is addressed by, and [armDirs] maps a token to the DIRECTORY its
 * states are committed in. The indirection exists for exactly one reason, and it is worth stating
 * plainly because it is the only place in this harness where a name means two things:
 *
 * **An arm token is a GLOBAL key, a directory name is a LOCAL one.** A probe build forwards three
 * parameters — `arm`, `index`, `replicate` — and all three are declared in a separate repository's
 * TeamCity DSL, so a fourth `case` coordinate cannot be added without a cross-repo commit landing
 * first. The case therefore has to be recoverable from the arm token alone, which makes the token space
 * flat: no two cases may share one. The on-disk layout is not flat — it is already keyed by case — so
 * `feature-service-125/mcp` and `rename-method-wide/mcp` never collided and both were committed under
 * the name `mcp`.
 *
 * That collision is settled in favour of the measured case. `mcp`, `none`, `mcp2` and `none2` belong to
 * `feature-service-125`: every number in the round-1 and round-2 write-ups is keyed by them, and a
 * token that changed meaning would silently re-point those results at another trajectory. The keycloak
 * case was discarded after stage 1 (its solution is atomic — see [RippleCheckpointCase]) and its states
 * are kept only so the discarded measurement stays checkable, so it is the one that yields: it is
 * registered under the distinct tokens `mcp-rmw`/`none-rmw` while its committed directories keep the
 * names `mcp`/`none` they were committed with. Renaming the directories was the alternative and was
 * rejected — the states are committed data, and moving them would rewrite files this change is required
 * to leave byte-identical.
 *
 * Every other case is registered with a token that already carries its own prefix, so [armDirs] stays
 * empty for all of them and token and directory are the same string.
 */
data class RippleCheckpointCaseSpec(
    val resourceDir: String,
    val instanceId: String,
    val arms: List<String>,
    val armDirs: Map<String, String> = emptyMap(),
) {
    /**
     * The directory [arm]'s states are committed in — the token itself unless [armDirs] renames it.
     *
     * An unregistered token is an error rather than a directory named after it: the caller has an arm
     * this case never captured, and inventing a path for it would report "no checkpoints committed" for
     * a directory that was never supposed to exist.
     */
    fun armDir(arm: String): String {
        require(arm in arms) {
            "the case '$resourceDir' has no arm '$arm' — its arms are $arms"
        }
        return armDirs[arm] ?: arm
    }

    /** The committed directory names of this case, in the order [arms] lists their tokens. */
    val armDirectories: List<String> get() = arms.map { armDir(it) }
}

/**
 * Every case the checkpoint harness can capture and probe.
 *
 * The first two are the pilot's: `feature-service-125` is what rounds 1 and 2 measured and the only
 * one carrying a second capture (`mcp2`/`none2`), and `rename-method-wide` is the discarded keycloak
 * case, kept because a published rejection has to stay checkable. The remaining six are round 3's
 * generalization — the harness stopped being an instrument for one case the moment a single-case
 * finding had to be told apart from a property of that case.
 *
 * The order is the order the cases were added, which is also the order the six new ones will be
 * captured in. Nothing depends on it; it is stable so that a diff of this list reads as an addition.
 */
object RippleCheckpointCases {
    val ALL: List<RippleCheckpointCaseSpec> = listOf(
        RippleCheckpointCaseSpec(
            resourceDir = RippleCheckpointCase.RESOURCE_DIR,
            instanceId = RippleCheckpointCase.INSTANCE_ID,
            // Round 1 and round 2 of the measured case. The round is encoded in the token for the same
            // reason the case now is — see RIPPLE_CHECKPOINT_ROUND2_ARMS.
            arms = listOf("mcp", "none", "mcp2", "none2"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "rename-method-wide",
            instanceId = RippleCases.renameMethodWide.instanceId,
            // Distinct tokens over directories that keep their committed names — see
            // RippleCheckpointCaseSpec for why this one case yields and the measured one does not.
            arms = listOf("mcp-rmw", "none-rmw"),
            armDirs = mapOf("mcp-rmw" to "mcp", "none-rmw" to "none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "petclinic-71",
            instanceId = "dpaia__spring__petclinic-71",
            arms = listOf("pc71-mcp", "pc71-none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "petclinic-rest-37",
            instanceId = "dpaia__spring__petclinic__rest-37",
            arms = listOf("pcr37-mcp", "pcr37-none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "petclinic-36",
            instanceId = "dpaia__spring__petclinic-36",
            arms = listOf("pc36-mcp", "pc36-none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "springboot3-1",
            instanceId = "dpaia__empty__maven__springboot3-1",
            arms = listOf("sb31-mcp", "sb31-none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "jhipster-3",
            instanceId = "dpaia__jhipster__sample__app-3",
            arms = listOf("jh3-mcp", "jh3-none"),
        ),
        RippleCheckpointCaseSpec(
            resourceDir = "feature-service-25",
            instanceId = "dpaia__feature__service-25",
            arms = listOf("fs25-mcp", "fs25-none"),
        ),
    )
}

/** Every arm token the harness knows, across all cases, in registry order. */
val RIPPLE_CHECKPOINT_ALL_ARMS: List<String> = RippleCheckpointCases.ALL.flatMap { it.arms }

/**
 * The case an arm token belongs to — the inversion the whole token scheme exists for.
 *
 * A probe build knows nothing but its three coordinates, so this lookup is what turns `arm=pc71-mcp`
 * into a resource directory and a dataset instance id. An unknown token is an error listing the known
 * ones and never a default: defaulting would run a full probe cell against the wrong case's states and
 * publish the result under the coordinate that was asked for.
 */
fun rippleCheckpointCaseOfArm(arm: String): RippleCheckpointCaseSpec =
    RippleCheckpointCases.ALL.firstOrNull { arm in it.arms }
        ?: error(
            "no checkpoint case is registered for the arm '$arm' — the known arms are " +
                "$RIPPLE_CHECKPOINT_ALL_ARMS"
        )
