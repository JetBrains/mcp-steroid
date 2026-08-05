import java.net.URI

// TeamCity-only: the ephemeral TC Mac agents (Equinix `icri-big-agent-eqx-*` farm) share one NAT
// egress IP that Maven Central rate-limits (HTTP 429) on cold dependency resolution; Gradle then
// disables the repository for the whole build, so every following artifact fails instantly
// (TC builds 1021765868 / 1022389938 / 1022420334 — all dead in ~19s inside :buildSrc, on the
// Gradle-distribution-pinned kotlin-dsl -> Kotlin 2.3.21 chain this repo does not even declare).
// The Linux/Windows AWS agents resolve the identical graph cold but egress from distributed IPs;
// they are one IP-reputation change away from the same failure, so the reroute covers every lane.
//
// Route only the hosts VERIFIED mirrored by the JetBrains cache redirector (2026-08-05: 307 ->
// artifacts-caching-proxy.aws.intellij.net -> 200 for the exact failing POM).
// packages.jetbrains.team is NOT mirrored (404) and is JetBrains-hosted anyway — stays direct.
//
// Gated on TEAMCITY_VERSION alone (not CI): GitHub Actions and local builds stay byte-identical.
val mirroredHosts = setOf("repo.maven.apache.org", "repo1.maven.org", "plugins.gradle.org")

if (System.getenv("TEAMCITY_VERSION") != null) {
    gradle.allprojects {
        fun reroute(repositories: RepositoryHandler) {
            repositories.withType<MavenArtifactRepository>().configureEach {
                val original = url
                if (original.scheme == "https" && original.host in mirroredHosts) {
                    url = URI("https://cache-redirector.jetbrains.com/${original.host}${original.path}")
                }
            }
        }
        reroute(repositories)
        reroute(buildscript.repositories)
    }
}
