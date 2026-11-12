/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.lucky

/**
 * The fork release an installed copy runs as, and the upstream releases it contains.
 *
 * The manager names itself after the upstream release it is built from, so an installed copy always
 * reports that upstream version: a fork sitting on 2.5.0 says 2.5.0 though it carries more than
 * upstream's. The fork version is what tells the two apart, and the fork's releases are tagged with
 * it, see [app.morphe.manager.lucky.LuckyBuildVersion].
 */
object LuckyUpstreamReleases {
    /** The newest upstream Morphe Manager release this build is based on. */
    val latest: String = "2.5.0"

    /**
     * Upstream releases put out after [latest], as this build knows them. The merge onto a newer
     * upstream release updates this list, and anything it lists here is a newer build.
     */
    val newerThanThisBuild: List<String> = emptyList()
}