package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ofPattern

/**
 * Wire the `--debug` flag into the bundled logback configuration.
 * The `devrig.log.level` system property is read at logback-init time
 * (see `logback.xml`). The default level is WARN. With `--debug`, the level
 * is DEBUG.
 *
 * MUST run before the first SLF4J call — logback initialises lazily on first
 * use and pins the level. [main] calls this right after command parsing for
 * exactly that reason.
 */
fun applyDebugLogging(debug: Boolean) {
    // Only set the property when --debug is requested — leaving it unset lets
    // operators override the WARN default from the outside with
    // `-Ddevrig.log.level=INFO` etc. The hard-coded default in logback.xml
    // (`${devrig.log.level:-WARN}`) handles the no-flag case.
    if (debug) {
        System.setProperty("devrig.log.level", "DEBUG")
    }
}

private class DevrigLog

fun configureLoggingAndLogStarted(homePaths: HomePaths, rawArgs: List<String>, debug: Boolean) {
    //configure loggers ASAP — these properties are read at logback init (the first SLF4J call below).
    val pid = ProcessHandle.current().pid()
    System.setProperty("devrig.log.dir", homePaths.logsDir.toString())
    // The PID is in BOTH the session (so every devrig process writes its OWN file — a log monitor detects
    // each as a new file) and the log-line pattern (so interleaved output is attributable to a process).
    System.setProperty("devrig.log.session", "${LocalDateTime.now().format(ofPattern("yyyy-MM-dd-HHmmss"))}-pid$pid")
    System.setProperty("devrig.pid", pid.toString())
    applyDebugLogging(debug)

    val log = logger<DevrigLog>()
    log.info("Starting Devrig ${DevrigVersionMetadata.getDevrigVersion()} (pid=$pid) with home paths: $homePaths and args: ${rawArgs.joinToString(" ")}")
}
