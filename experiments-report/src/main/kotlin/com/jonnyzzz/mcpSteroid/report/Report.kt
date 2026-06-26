package com.jonnyzzz.mcpSteroid.report

/** Everything the renderer needs: the primary with/without comparisons plus every run for the secondary views. */
data class Report(
    val title: String,
    val generatedAt: String,
    val comparisons: List<Comparison>,
    val allRuns: List<AgentRun>,
)

/** Per-agent roll-up of the with/without verdicts, shown at the top of the dashboard. */
data class AgentSummary(
    val agent: String,
    val helped: Int,
    val hurt: Int,
    val neutral: Int,
    val incomplete: Int,
) {
    val total: Int get() = helped + hurt + neutral + incomplete
}

/** One mined problem across runs, ranked for the "Top problems" section. */
data class Problem(
    val title: String,
    val count: Int,
    val detail: String,
)
