package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Shared helpers for debugger integration tests.
 */

fun hasAnyMarkerLine(output: String, vararg markers: String): Boolean {
    return markers.any { marker ->
        Regex("""(?im)^\s*[*_`>#-]*\s*${Regex.escape(marker)}\s*:""").containsMatchIn(output)
    }
}

fun findMarkerValue(output: String, vararg markers: String): String? {
    if (markers.isEmpty()) return null
    val markerAlternation = markers.joinToString("|") { Regex.escape(it) }
    val markerRegex = Regex(
        pattern = """(?im)^\s*[*_`>#-]*\s*(?:$markerAlternation)\s*:\s*(.+?)\s*[*_`]*\s*$"""
    )
    val candidates = markerRegex.findAll(output).mapNotNull { match ->
        match.groupValues
            .getOrNull(1)
            ?.trim()
            ?.trim('*', '_', '`')
            ?.takeIf { it.isNotEmpty() }
    }.toList()

    // Filter out template placeholders like <the exact buggy source line>
    // but allow code type params (<Player>, <T>) and C# lambdas (p => p.Score)
    // Template placeholders always contain spaces; code type params don't
    val templatePlaceholder = Regex("""<[a-zA-Z][^>]*\s[^>]*>""")
    return candidates.lastOrNull { value ->
        val lowered = value.lowercase()
        !templatePlaceholder.containsMatchIn(value) &&
                !lowered.contains("copy the") &&
                !lowered.contains("one line description") &&
                !lowered.contains("exact buggy source line")
    }
}

fun assertUsedExecuteCodeEvidence(combined: String) {
    val executionIdPattern = Regex("""\b(?:Execution ID|execution_id):\s*eid_[A-Za-z0-9_-]+""")
    val hasToolEvidence = executionIdPattern.containsMatchIn(combined)

    check(hasToolEvidence) {
        "Agent must show evidence of steroid_execute_code usage.\n" +
                "Expected an execution id marker (`Execution ID: eid_...` or `execution_id: eid_...`).\nOutput:\n$combined"
    }
}

fun assertRootCauseQuality(
    combined: String,
    output: String,
    firstAspectPatterns: List<String>,
    secondAspectPatterns: List<String>,
    explanation: String,
) {
    val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
    check(rootCause != null) {
        "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
    }

    val mentionsFirstAspect = firstAspectPatterns.any { pattern ->
        rootCause.contains(pattern, ignoreCase = true)
    }
    val mentionsSecondAspect = secondAspectPatterns.any { pattern ->
        rootCause.contains(pattern, ignoreCase = true)
    }
    check(mentionsFirstAspect && mentionsSecondAspect) {
        "$explanation\n" +
                "Expected first-aspect patterns: $firstAspectPatterns\n" +
                "Expected second-aspect patterns: $secondAspectPatterns\n" +
                "Actual ROOT_CAUSE: $rootCause\nOutput:\n$combined"
    }
}