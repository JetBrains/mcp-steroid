/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * Curated set of dpaia.dev arena test cases selected for MCP Steroid comparison testing.
 *
 * Selection criteria:
 * - Requires understanding codebase structure (not just grep/search)
 * - Benefits from IDE navigation, semantic analysis, or compilation feedback
 * - Complex enough that IntelliJ tooling provides a measurable advantage
 * - The project must be able to open in IntelliJ IDEA without manual setup
 *
 * Dataset source:
 * https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific case:
 *   -Darena.test.instanceId=dpaia__spring__petclinic__rest-14
 */
object DpaiaCuratedCases {

    /** Classifies the primary challenge driving agent effort for a test case. */
    enum class TaskType {
        /** Agent must find and modify many spread-out locations. IDE navigation pays off. */
        NAVIGATE_MODIFY,
        /** Agent mostly knows what to write from training; search scope is narrow. */
        IMPLEMENT_SCRATCH,
        /** Mixed characteristics. */
        MIXED,
    }

    /**
     * Measured cost of the `mcp` arm relative to its own `none` control on the same case.
     *
     * **Set this from measurement, never from expectation.** The values here were a-priori guesses until
     * 2026-08-10, and 24 paired Opus 5 runs contradicted them: the three cases guessed `HIGH` averaged
     * 1.24x the control's cost, while the `LOW` and `UNKNOWN` ones averaged 0.84x. A case nobody has run
     * stays [UNKNOWN] — that is the honest state, and it is what keeps headline numbers from being
     * computed over a set selected by guesswork.
     *
     * What the measurements actually predict, on the paired runs available so far: the arm's saving grows
     * with the SIZE of the task, because the IDE path trades a fixed per-call overhead for a variable
     * saving on context replay. The two cases where it wins are the two most expensive ones
     * (`petclinic-71` 0.64x at $7-11 per arm, `feature__service-125` 0.79x at $3-4); everything at ~$1 per
     * arm loses at 1.3-1.8x. The second factor is whether there is an existing codebase to navigate:
     * `empty__maven__springboot3-1` is the worst case of the whole set (1.76x) despite being tagged HIGH,
     * and it is greenfield — nothing to index, nothing to find, so the IDE arm pays overhead for no
     * navigation. Cross-layer reach, which the old tags were guessing at, did not separate the cases.
     */
    enum class McpBenefit {
        /** Measured cheaper than the control by more than 10% on at least 3 paired runs. */
        HIGH,
        /** Measured no better than the control (within 10%, or worse). */
        LOW,
        /** Never run as a pair, or fewer than 3 passes — no claim either way. */
        UNKNOWN,
    }

    /**
     * Per-case configuration overrides for resource-intensive test cases.
     *
     * @param projectReadyTimeoutMs  Timeout for [IntelliJContainer.waitForProjectReady], ms. Default 600 000 (10 min).
     * @param agentTimeoutSeconds    Agent run timeout passed to [ArenaTestRunner.runTest]. Default 900 (15 min).
     * @param projectJdkVersion       JDK version to set as the IDE project SDK before import. Default 21.
     * @param taskType               Primary challenge type driving agent effort.
     * @param mcpBenefit             Observed MCP benefit from A/B comparison runs.
     * @param overlayTestPatch       Classpath resource with a local test patch applied AFTER the dataset test patch.
     * @param overlayFailToPass      FAIL_TO_PASS classes added by [overlayTestPatch].
     * @param dockerOracleWorks      True once the Testcontainers oracle is verified working in the arena
     *   container for this case. It suppresses every Docker-surrender paragraph in the prompt, so the
     *   agent cannot claim success on a compile check alone.
     */
    data class CaseConfig(
        val projectReadyTimeoutMs: Long = 600_000L,
        val agentTimeoutSeconds: Long = 900L,
        val projectJdkVersion: String = "21",
        val taskType: TaskType = TaskType.MIXED,
        val mcpBenefit: McpBenefit = McpBenefit.UNKNOWN,
        val overlayTestPatch: String? = null,
        val overlayFailToPass: List<String> = emptyList(),
        val dockerOracleWorks: Boolean = false,
    )

    /**
     * Augments [base] with the case's overlay test patch, if it declares one: the overlay is appended
     * after the dataset test patch and contributes its own FAIL_TO_PASS classes, so a locally authored
     * test can extend the oracle without editing dataset content. The returned instance id carries an
     * `x` suffix, which keeps an overlay-augmented run distinguishable in every report from a plain
     * dataset run of the same case.
     *
     * [readResource] resolves a classpath resource path to its content.
     */
    fun applyOverlay(
        base: DpaiaTestCase,
        config: CaseConfig,
        readResource: (String) -> String,
    ): DpaiaTestCase {
        val resourcePath = config.overlayTestPatch ?: return base
        return base.copy(
            instanceId = "${base.instanceId}x",
            testPatch = base.testPatch + "\n" + readResource(resourcePath),
            failToPass = base.failToPass + config.overlayFailToPass,
        )
    }

    /**
     * Per-case configuration overrides keyed by instance ID.
     *
     * Includes resource limits and A/B classification data from 16-case comparison run (2026-03-05).
     *
     * - **microshop-18**: 23-module project; default 10-min IDE-ready timeout is too short → 20 min.
     * - **petclinic-rest-3**: cache-eviction task; NONE agent needs more wall time → 90 min.
     * - **petclinic-71**: JPA→R2DBC migration; both agents timed out at 30 min → 90 min.
     *
     * **Two vintages of [McpBenefit] live in this map.** Entries carrying a `measured Nx over N passes`
     * comment come from the 2026-08 Opus 5 paired rounds and are the current, reproducible ones. Entries
     * without such a comment that are not [McpBenefit.UNKNOWN] — `petclinic__rest-14`,
     * `petclinic__microservices-5`, `petclinic__rest-37` — still carry the 2026-03-05 classification, taken
     * on a different model and a much older harness (before the verifier fixes, before objective_success).
     * Treat them as provenance, not as a current measurement, and re-measure before quoting them.
     */
    val CASE_CONFIGS: Map<String, CaseConfig> = mapOf(
        // Batch 1
        "dpaia__feature__service-125" to CaseConfig(
            agentTimeoutSeconds = 1_800L,
            projectJdkVersion = "24",
            taskType = TaskType.NAVIGATE_MODIFY,
            mcpBenefit = McpBenefit.HIGH,   // measured 0.79x over 4 passes, and 0.82x over 4 more
            overlayTestPatch = "arena-overlays/dpaia__feature__service-125.patch",
            overlayFailToPass = listOf("com.sivalabs.ft.features.config.ReleaseApiSecuritySliceTest"),
            dockerOracleWorks = true,
        ),
        "dpaia__empty__maven__springboot3-1" to CaseConfig(
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.LOW,   // measured 1.76x over 3 passes — worst in the set, and greenfield
        ),
        "dpaia__feature__service-25" to CaseConfig(
            // The project needs release 24: on 21 the grading run compiled nothing and reported 0/1 for
            // both arms, while the baseline's own retry found 8 passing classes on 24. service-125, the
            // same repository, has carried 24 all along.
            projectJdkVersion = "24",
            taskType = TaskType.NAVIGATE_MODIFY,
            // Never run as a pair; the old HIGH was a guess. Queued as the falsification case for the
            // size hypothesis: same repo and shape as service-125, but one FAIL_TO_PASS class and 17 KB
            // of patch, so if scale is what decides, this one must NOT win.
            mcpBenefit = McpBenefit.UNKNOWN,
        ),
        "dpaia__spring__petclinic__rest-14" to CaseConfig(
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.LOW,
        ),
        "dpaia__spring__boot__microshop-1" to CaseConfig(
            projectJdkVersion = "24",
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.UNKNOWN,
        ),
        // Batch 2
        "dpaia__spring__petclinic-36" to CaseConfig(
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.LOW,   // measured 1.04x over 3 passes — guess confirmed
        ),
        "dpaia__jhipster__sample__app-3" to CaseConfig(
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.LOW,   // measured 1.30x over 3 passes — was a HIGH guess
        ),
        // train-ticket predates JDK 21: its Lombok crashes javac with
        // `NoSuchFieldError: Class com.sun.tools.javac.tree.JCTree$JCImport … member field 'qualid'`,
        // which killed `ts-common` and skipped every downstream module — including the one holding the
        // FAIL_TO_PASS tests, so verification graded 0/2 while the agent's own run was green. Measured
        // over the gpt-5.6 pass: on JDK 21 not one of 40 Maven test invocations succeeded; JDK 11
        // produced green FAIL_TO_PASS runs (30 tests, 0 failures) without needing jacoco workarounds,
        // which JDK 17 did require.
        "dpaia__train__ticket-1" to CaseConfig(
            projectJdkVersion = "11",
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.LOW,   // measured 1.63x over 3 passes
        ),
        "dpaia__train__ticket-31" to CaseConfig(
            projectJdkVersion = "11",
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.LOW,   // measured 1.61x over 3 passes — guess confirmed
        ),
        "dpaia__spring__boot__microshop-18" to CaseConfig(
            projectReadyTimeoutMs = 1_200_000L,
            // The biggest task in the pool: 60 KB of patch over 23 files in 3 modules. The default 15
            // minutes killed the MCP arm at exit=-1 without a claim, the same 90 minutes petclinic-71 and
            // petclinic-rest-3 already carry.
            agentTimeoutSeconds = 5_400L,
            projectJdkVersion = "24",
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.UNKNOWN,   // 60 KB / 23 files / 3 modules — biggest unrun task, queued
        ),
        "dpaia__spring__boot__microshop-2" to CaseConfig(
            projectJdkVersion = "24",
            // Never run as a pair; the old HIGH was a guess. Queued: the only case whose FAIL_TO_PASS
            // classes sit in 4 packages across 3 Maven modules, which is where text search degrades
            // fastest and an index lookup should pay for itself.
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.UNKNOWN,
        ),
        "dpaia__spring__petclinic-27" to CaseConfig(
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.LOW,   // measured 1.04x over 3 passes — was a HIGH guess
        ),
        "dpaia__spring__petclinic__rest-3" to CaseConfig(
            agentTimeoutSeconds = 5_400L,
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.UNKNOWN,   // the control arm is the one that needs 90 min here, queued
        ),
        // Batch 3
        "dpaia__piggymetrics-6" to CaseConfig(
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.UNKNOWN,
        ),
        "dpaia__spring__petclinic__microservices-5" to CaseConfig(
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.LOW,
        ),
        "dpaia__spring__petclinic__rest-37" to CaseConfig(
            // 23 files, 37 KB of patch and 352 FAIL_TO_PASS tests do not land inside the default 900 s
            // in either arm, and a case that times out in BOTH is unusable for the checkpoint harness:
            // an agent killed before it finished leaves no edit phase to cut fractions of, so the
            // capture publishes no states and the probe has nothing to be restarted from. 1800 s is what
            // feature-service-125 — a smaller task on the same instrument — already needs.
            agentTimeoutSeconds = 1_800L,
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.LOW,
        ),
        "dpaia__spring__petclinic-71" to CaseConfig(
            agentTimeoutSeconds = 5_400L,
            taskType = TaskType.NAVIGATE_MODIFY, mcpBenefit = McpBenefit.HIGH,   // measured 0.64x over 3 passes — the largest task in the set
        ),
        "dpaia__empty__maven__springboot3-3" to CaseConfig(
            taskType = TaskType.IMPLEMENT_SCRATCH, mcpBenefit = McpBenefit.LOW,   // measured 1.65x over 3 passes
        ),
    )



    /** The default simple case — used as a quick smoke test. */
    const val DEFAULT_SIMPLE = "dpaia__empty__maven__springboot3-3"

    /**
     * Curated shortlist for evaluation runs.
     *
     * Selection criteria: large patch (complex multi-file change), multi-layer
     * (entity + service + controller + migration), and scenarios where IDE tools
     * (Find Usages, compile feedback, run tests) provide measurable advantage.
     *
     * Dataset: https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json
     * (154 cases across 11 repos as of 2026-02-21)
     *
     * | instanceId                              | Repo                  | Patch  | Why MCP helps                                   |
     * |-----------------------------------------|-----------------------|--------|--------------------------------------------------|
     * | dpaia__feature__service-125             | feature-service       | 44 KB  | Cross-layer queries + status machine; PSI nav    |
     * | dpaia__feature__service-122             | feature-service       | 31 KB  | Full notification subsystem; pattern reuse       |
     * | dpaia__empty__maven__springboot3-1      | empty-maven-springboot| 28 KB  | JWT from scratch; Spring Security API resolution |
     * | dpaia__feature__service-25              | feature-service       | 17 KB  | Self-referential JPA; circular dep detection     |
     * | dpaia__feature__service-22              | feature-service       | 15 KB  | FeatureReactionController; IDE Find Usages       |
     * | dpaia__empty__maven__springboot3-3      | empty-maven-springboot| 15 KB  | Product entity; jakarta vs javax validation      |
     * | dpaia__feature__service-21              | feature-service       | 13 KB  | Comments/replies; multi-controller consistency   |
     * | dpaia__spring__petclinic__rest-23       | spring-petclinic-rest | —      | Password policy; IDE per-class test isolation    |
     * | dpaia__spring__petclinic__rest-14       | spring-petclinic-rest | —      | 575 failing tests; IDE compile check             |
     * | dpaia__spring__boot__microshop-1        | spring-boot-microshop | —      | WebClient migration; IDE Find Usages             |
     */
    val COMPARISON_CASES: List<String> = listOf(
        "dpaia__feature__service-125",
        "dpaia__feature__service-122",
        "dpaia__empty__maven__springboot3-1",
        "dpaia__feature__service-25",
        "dpaia__feature__service-22",
        "dpaia__empty__maven__springboot3-3",
        "dpaia__feature__service-21",
        "dpaia__spring__petclinic__rest-23",
        "dpaia__spring__petclinic__rest-14",
        "dpaia__spring__boot__microshop-1",
    )

    /**
     * Primary comparison cases for the A/B comparison: "agent with MCP Steroid" vs "agent without".
     *
     * **Original 4 cases (Batch 1, 2026-03-02):**
     * - feature-service-125: 44 KB patch; JPQL + status machine spanning entities/services/controllers
     * - empty-maven-springboot3-1: JWT auth from scratch; Spring Security API versioning traps
     * - feature-service-25: self-referential JPA hierarchy; circular dependency caught at compile time
     * - spring-petclinic-rest-14: 575 failing tests; IDE highlights every missed edit immediately
     *
     * **8 new cases (Batch 2, Group 4 expansion):**
     * Selected via dataset analysis (group4-analyze-dataset.py) covering 3 new repos and diverse
     * task types. See docs/dpaia-runs/EXEC-SUMMARY.md for selection rationale.
     *
     * **4 new cases (Batch 3, 2026-03-04):**
     * Two brand-new repos (piggymetrics, spring-petclinic-microservices) plus two large multi-file
     * refactors where semantic IDE analysis provides the highest measurable advantage.
     *
     * | instanceId                              | Repo                    | FTP | Files | Why MCP helps                                   |
     * |-----------------------------------------|-------------------------|-----|-------|--------------------------------------------------|
     * | dpaia__spring__petclinic-36             | spring-petclinic (NEW)  |  4  |   8   | Add email field: entity+schema+form across layers|
     * | dpaia__jhipster__sample__app-3          | jhipster (NEW)          |  4  |   9   | Rename ROLE_ADMIN; IDE Find Usages critical      |
     * | dpaia__train__ticket-1                  | train-ticket (NEW)      |  3  |   4   | Extend OrderRepository with date-range JPQL      |
     * | dpaia__train__ticket-31                 | train-ticket (NEW)      |  2  |   8   | Extend PaymentRepository + service layer         |
     * | dpaia__spring__boot__microshop-18       | spring-boot-microshop   |  8  |  23   | RestTemplate→WebClient migration; 23 files       |
     * | dpaia__spring__boot__microshop-2        | spring-boot-microshop   |  4  |  10   | Add productId validation across all microservices|
     * | dpaia__spring__petclinic-27             | spring-petclinic (NEW)  |  3  |   4   | Build REST endpoints for owners/pets/visits      |
     * | dpaia__spring__petclinic__rest-3        | spring-petclinic-rest   |  3  |   5   | Cache eviction + scheduled invalidation          |
     * | dpaia__piggymetrics-6                   | piggymetrics (NEW)      |306  |   6   | TestContainers migration; multi-module Maven nav |
     * | dpaia__spring__petclinic__microservices-5 | petclinic-microservices (NEW) | 167 | 3 | Circuit breaker+timeout; Resilience4j API nav  |
     * | dpaia__spring__petclinic__rest-37       | spring-petclinic-rest   |352  |  23   | Pagination across all REST endpoints; 37 KB      |
     * | dpaia__spring__petclinic-71             | spring-petclinic        |286  |  23   | JPA→R2DBC reactive migration; semantic analysis  |
     */
    val PRIMARY_COMPARISON_CASES: List<String> = listOf(
        // Batch 1: original 4 cases
        "dpaia__feature__service-125",
        "dpaia__empty__maven__springboot3-1",
        "dpaia__feature__service-25",
        "dpaia__spring__petclinic__rest-14",

        // Batch 2: 8 new cases from Group 4 expansion
        // NEW repo: spring-petclinic — add email field to Owner across entity/DB schema/form (REST+Validation)
        "dpaia__spring__petclinic-36",
        // NEW repo: jhipster-sample-app — rename ROLE_ADMIN to ROLE_ADMINISTRATOR; Find Usages ideal (Security, 9 files)
        "dpaia__jhipster__sample__app-3",
        // NEW repo: train-ticket — extend OrderRepository with accountId+date-range JPQL queries (Data+JPA)
        "dpaia__train__ticket-1",
        // NEW repo: train-ticket — extend PaymentRepository with date-range + update service layer (Controller+JPA)
        "dpaia__train__ticket-31",
        // spring-boot-microshop — replace RestTemplate with WebClient across ALL microservices (23 files, ftp=8)
        "dpaia__spring__boot__microshop-18",
        // spring-boot-microshop — add productId validation across all microservices (REST+Validation, 10 files)
        "dpaia__spring__boot__microshop-2",
        // NEW repo: spring-petclinic — build REST endpoints for owners/pets/visits (feature addition, 4 files)
        "dpaia__spring__petclinic-27",
        // spring-petclinic-rest — add cache eviction + scheduled invalidation (Cache, 5 files)
        "dpaia__spring__petclinic__rest-3",

        // Batch 3: 4 new cases — 2 brand-new repos + 2 large multi-file refactors (2026-03-04)
        // NEW repo: piggymetrics — switch from flapdoodle to TestContainers across 6 files (ftp=306)
        // Multi-module Maven project; IDE navigation needed to find + update all test base classes
        "dpaia__piggymetrics-6",
        // NEW repo: spring-petclinic-microservices — add circuit breaker + timeout to feign clients (ftp=167)
        // Resilience4j annotations + config; IDE type-checks Spring Cloud API across microservices
        "dpaia__spring__petclinic__microservices-5",
        // spring-petclinic-rest — implement pagination for all pet endpoints; 23 files, 37 KB, ftp=352
        // Highest FTP in batch; consistent Pageable adoption requires IDE Find Usages across all controllers
        "dpaia__spring__petclinic__rest-37",
        // spring-petclinic — migrate persistence layer from JPA to R2DBC; 23 files, 37 KB, ftp=286
        // Architectural refactor: replace JpaRepository + @Entity with ReactiveCrudRepository + R2DBC;
        // IDE semantic analysis distinguishes reactive vs blocking APIs at every callsite
        "dpaia__spring__petclinic-71",
    )
}
