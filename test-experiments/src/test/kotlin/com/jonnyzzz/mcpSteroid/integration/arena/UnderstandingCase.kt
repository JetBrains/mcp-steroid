/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One task of the repository-understanding experiment: a real feature to add to a large unfamiliar
 * tree, plus everything the harness needs to deploy, run and grade it.
 *
 * A type of its own instead of a [RippleCase], because a [RippleCase] is a REFACTORING case: its
 * fields are a transformation target, a resolved-reference count and a text-ambiguity reading, and its
 * grade is a PSI postcondition. This experiment deliberately measures the opposite kind of task — one
 * where the answer is an architecture to imitate rather than a set of references to find — so it
 * shares the environment (see [SemanticRippleSpec]) and nothing of the oracle.
 *
 * A plain [DpaiaTestCase] would also have worked mechanically, but it would have hidden the two
 * constraints this experiment stands or falls on, which are therefore fields here and are asserted at
 * construction: the statement must not name the files to change ([statementLeakageTokens] must be the
 * complete list of what a reader can grep out of it), and the grade must be a scoped Maven run
 * ([gradingScopeSelector]), because a whole-suite baseline on Keycloak costs forty minutes per cell and
 * this experiment pays for many cells.
 */
data class UnderstandingCase(
    val instanceId: String,
    /** The behaviour-level brief, with no target file, class or package in it. */
    val problemStatement: String,
    /**
     * Classpath resource of the patch that adds the hidden oracle test to the tree, or null when the
     * case is RESEARCH-ONLY.
     *
     * Null is a declaration, not an omission. The acquisition rounds measure `U(B)` — how much of a
     * pre-registered architecture checklist an agent holds after B interactions — and that endpoint
     * needs a statement and a checklist, nothing else. Requiring an oracle for every case would price
     * a generalization round at one hidden test suite per case that no cell of the round would run,
     * which is exactly how a family stays at one case forever.
     *
     * What null costs is that such a case could be queued into a phase that cannot grade it, so the
     * type refuses rather than degrading: [oracleTestPatch] and [dpaiaCase] throw, and the downstream
     * cell asks [gradable] before it spends a container minute.
     */
    val oracleTestPatchResource: String? = null,
    /** FAIL_TO_PASS entries, in the form [ArenaVerifier] parses; empty for a research-only case. */
    val failToPass: List<String> = emptyList(),
    /**
     * How many assertions the hidden oracle makes, counted in the patch.
     *
     * The denominator of the residual-work reading, and it has to come from the CASE rather than from
     * the run: a downstream cell whose module failed to compile executes zero tests, and scoring it
     * "0 of 0" would silently drop the very worst outcomes out of every average it enters. Pinned
     * against the patch by `UnderstandingCaseRegistryTest`, so an oracle that grows an assertion
     * cannot leave the denominator behind.
     */
    val oracleTestCount: Int = 0,
    /** Reactor projects the grading run is scoped to, e.g. `:keycloak-server-spi`. */
    val gradingScopeSelector: String,
    /**
     * Every word of [problemStatement] that could be grepped straight onto a target file, with the
     * number of files that grep returns at the base commit.
     *
     * This is the leakage audit of criterion A, kept as data and not as prose: a statement that names
     * one word matching three files has already handed the agent its localization, and the experiment
     * would then compare two notes about a task that needed no research. Checked by
     * `UnderstandingCaseRegistryTest` against the threshold both arms are admitted under.
     */
    val statementLeakageTokens: Map<String, Int>,
    /** Where the analogous existing feature lives — provenance for the design, never sent to an agent. */
    val precedentPaths: List<String>,
    /** The gold change set by ROLE, as measured when the case was authored — provenance, never sent. */
    val goldRolePaths: Map<String, List<String>>,
    val cloneUrl: String = SemanticRippleSpec.cloneUrl,
    val repoOwnerAndName: String = SemanticRippleSpec.repoOwnerAndName,
    val baseCommit: String = SemanticRippleSpec.baseCommit,
    val projectJdkVersion: String = SemanticRippleSpec.projectJdkVersion,
    val projectReadyTimeoutMs: Long = SemanticRippleSpec.projectReadyTimeoutMs,
    /**
     * The research phase's wall-clock backstop.
     *
     * Shorter than a solving run's budget on purpose: a research agent that has spent its interactions
     * has nothing left to do but write a note, so a long timeout here can only buy a stuck run.
     */
    val researchTimeoutSeconds: Long = 1_800L,
    /** The downstream agent's budget. The same in every arm, or the arms would not be comparable. */
    val downstreamTimeoutSeconds: Long = 3_600L,
    /**
     * True when the whole reactor must be `mvn install`ed before an agent can build one module.
     *
     * Keycloak's `999.0.0-SNAPSHOT` artifacts exist nowhere but the machine that built them, so
     * without this every `-pl` invocation — the agent's and the grader's alike — fails on a missing
     * upstream POM. See [SemanticRippleSpec.reactorInstallArgs].
     */
    val needsReactorInstall: Boolean = true,
) {
    init {
        check(instanceId.startsWith("understanding__") || instanceId.startsWith("acquisition__")) {
            "an understanding case id must be prefixed `understanding__` (the note-bottleneck rounds) " +
                "or `acquisition__` (the acquisition-curve rounds, see [AcquisitionCases]) so no report " +
                "can confuse it with a dpaia or ripple case: got '$instanceId'"
        }
        // All three or none of them. A case that carries an oracle patch but no FAIL_TO_PASS entry
        // would run the grading build and read nothing out of it, and a case that names a denominator
        // it has no oracle for would publish a fraction of an oracle that does not exist — both fail
        // as a percentage rather than as an error, which is the failure mode this experiment can least
        // afford.
        val oracleParts = listOf(
            "oracleTestPatchResource" to (oracleTestPatchResource != null),
            "failToPass" to failToPass.isNotEmpty(),
            "oracleTestCount" to (oracleTestCount > 0),
        )
        check(oracleParts.all { it.second } || oracleParts.none { it.second }) {
            "$instanceId is half a gradable case: ${oracleParts.filter { it.second }.map { it.first }} " +
                "given, ${oracleParts.filterNot { it.second }.map { it.first }} missing. A case is " +
                "either research-only (all three absent, U(B) is its only endpoint) or downstream-" +
                "gradable (all three present)"
        }
        check(gradingScopeSelector.startsWith(":")) {
            "a Maven `-pl` token without a colon is read as a directory path, not an artifactId, and " +
                "the grading run then fails with 'Could not find the selected project in the reactor': " +
                "got '$gradingScopeSelector'"
        }
        check(precedentPaths.isNotEmpty()) {
            "$instanceId claims to be an understanding task but names no precedent to be understood"
        }
    }

    /**
     * Whether a downstream solving cell can be graded on this case at all.
     *
     * Asked BEFORE a cell starts a container, because the alternative is a thirty-minute run whose
     * verdict is "0 of 0" — a number that enters an average as a zero and reads as a failure of the
     * agent rather than of the queue.
     */
    val gradable: Boolean get() = oracleTestPatchResource != null

    fun oracleTestPatch(): String {
        val resource = checkNotNull(oracleTestPatchResource) {
            "$instanceId is a research-only case: it has no hidden oracle, so nothing can be graded " +
                "against it. Its endpoint is the acquisition curve U(B); check `gradable` before " +
                "queueing it into a solving phase"
        }
        return checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "Oracle patch resource not found on the test classpath: $resource"
        }.use { it.readBytes().decodeToString() }
    }

    /**
     * The case in the shape the container setup, the agent runner and the verifier already consume.
     *
     * `passToPass` is empty for the same reason the ripple family leaves it empty: a whole-suite
     * baseline is not viable on this tree, so regressions are reported as UNKNOWN rather than
     * fabricated from an empty snapshot.
     */
    fun dpaiaCase(): DpaiaTestCase = DpaiaTestCase(
        instanceId = instanceId,
        issueNumbers = emptyList(),
        tags = listOf("Feature", "RepositoryUnderstanding"),
        repo = "$repoOwnerAndName.git",
        patch = "",
        testPatch = oracleTestPatch(),
        failToPass = failToPass,
        passToPass = emptyList(),
        createdAt = "2026-08-21T00:00:00Z",
        baseCommit = baseCommit,
        problemStatement = problemStatement,
        version = "1",
        isMaven = true,
        buildSystem = "maven",
        testArgs = "",
    )
}

/**
 * The brief for the e-mail-domain claim case, in the wording every arm receives.
 *
 * Behaviour only, and phrased in the vocabulary an administrator would use — "claim", "ID token",
 * "UserInfo", "out of the box" — so that the work of the research phase is to discover that a claim is
 * produced by a protocol mapper, that a mapper is found through a service file, and that "shipped with
 * the server" is a third, separate registration. Naming any of those three would hand over the whole
 * design, which is what [UnderstandingCase.statementLeakageTokens] exists to keep honest.
 */
private const val EMAIL_DOMAIN_MAPPER_STATEMENT: String =
    "Add an OpenID Connect token mapper that contributes a claim named `email_domain` whose value is the part " +
        "after `@` of the e-mail address carried by the token that is being issued; if the token has no e-mail, or " +
        "it contains no `@`, the claim must not be added. The mapper must honour the standard administrator " +
        "switches that decide whether a claim goes into the ID token, the access token, the UserInfo response and " +
        "token introspection, must expose its configuration for the admin console under the usual token-mapper " +
        "category, and must be offered out of the box: a freshly created realm's administrator must find it among " +
        "the mappers the server ships with, without creating it by hand. It must also be resolvable at runtime by " +
        "its provider id `oidc-email-domain-mapper`."

/**
 * The leakage audit of [EMAIL_DOMAIN_MAPPER_STATEMENT], measured at the base commit with
 * `grep -ril <token> --include='*.java'` over the clone with `.git` excluded.
 *
 * Two readings matter. The invented identifiers — `email_domain`, `oidc-email-domain-mapper` — find
 * nothing to copy: the single case-insensitive hit for the first is `EmailValidationUtil`'s
 * `EMAIL_DOMAIN_PATTERN`, an e-mail syntax validator that has nothing to do with claims, so it is a
 * decoy rather than a shortcut. The narrowest real phrase is `token mapper` at two files, one of which
 * IS the mapper base class — unavoidable, because a statement that may not say "protocol mapper" still
 * has to say what kind of thing to add, and two files is a hint about the base class only, not about
 * either registration, which is where this case is actually won or lost.
 */
private val EMAIL_DOMAIN_MAPPER_LEAKAGE: Map<String, Int> = mapOf(
    "email_domain" to 1,
    "oidc-email-domain-mapper" to 0,
    "token mapper" to 2,
    "token-mapper" to 3,
    "e-mail" to 32,
    "OpenID Connect" to 35,
    "provider id" to 52,
    "ID token" to 81,
    "introspection" to 118,
    "access token" to 151,
    "UserInfo" to 158,
    "claim" to 523,
)

/**
 * Every case this experiment can run, addressed by id.
 *
 * A registry rather than a constant, because the pilot runs ONE case and the design explicitly keeps
 * the door open for a second one: the whole point of the note format and the budget grid is that they
 * are properties of the instrument, not of the task.
 */
object UnderstandingCases {
    val ALL: List<UnderstandingCase> = listOf(
        UnderstandingCase(
            instanceId = "understanding__keycloak__email-domain-mapper",
            problemStatement = EMAIL_DOMAIN_MAPPER_STATEMENT,
            oracleTestPatchResource = "arena-overlays/understanding-keycloak-email-domain-mapper.patch",
            failToPass = listOf("org.keycloak.protocol.oidc.EmailDomainMapperContractTest"),
            oracleTestCount = 7,
            gradingScopeSelector = ":keycloak-services",
            statementLeakageTokens = EMAIL_DOMAIN_MAPPER_LEAKAGE,
            precedentPaths = listOf(
                "services/src/main/java/org/keycloak/protocol/oidc/mappers/HardcodedClaim.java",
                "services/src/main/java/org/keycloak/protocol/oidc/mappers/UserAttributeMapper.java",
                "services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocolFactory.java",
            ),
            goldRolePaths = mapOf(
                "behaviour" to listOf("services/src/main/java/org/keycloak/protocol/oidc/mappers/"),
                "discovery" to listOf(
                    "services/src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper",
                ),
                "builtin-registration" to listOf(
                    "services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocolFactory.java",
                ),
            ),
        ),
    )

    fun of(instanceId: String): UnderstandingCase = ALL.firstOrNull { it.instanceId == instanceId }
        ?: error(
            "'$instanceId' is not a registered understanding case. Known: " +
                (ALL.joinToString { it.instanceId }.ifEmpty { "<none yet>" })
        )
}
