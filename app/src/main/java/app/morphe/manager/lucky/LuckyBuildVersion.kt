/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.lucky

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * How a manager release of this fork names itself.
 *
 * The manager names itself after the upstream release it contains, so an installed copy always
 * reports that upstream version. The fork tag therefore says both things at once,
 * `v<upstream>-lp.<fork build>`: two copies on the same upstream release are told apart by what
 * follows `-lp.`, and an installed copy left behind by a newer upstream tells which upstream it
 * is behind.
 */
object LuckyBuildVersion {
    const val MARKER = "-lp."

    /** Whether [latest] supersedes [installed], upstream release first and fork build after it. */
    fun isNewer(latest: String, installed: String): Boolean {
        val byUpstream = compareUpstream(upstreamPart(latest), upstreamPart(installed))
        return when {
            byUpstream != 0 -> byUpstream > 0
            else -> (buildPart(latest) ?: 0) > (buildPart(installed) ?: 0)
        }
    }

    /** A version with anything past its first `-` dropped, as an upstream release names itself. */
    fun upstreamPart(version: String): String = version.substringBefore('-')

    /** The fork build in [version], or null for an upstream release. */
    fun buildPart(version: String): Int? =
        version.substringAfter(MARKER, "")
            .substringBefore('-')
            .takeIf { it.isNotBlank() }
            ?.toIntOrNull()

    private fun compareUpstream(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toIntOrNull() ?: 0
            val y = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}

@Serializable
private data class ManagerTag(val name: String)

@Serializable
private data class ManagerTags(val tags: List<ManagerTag> = emptyList())

@Serializable
private data class ManagerAsset(val name: String)

@Serializable
private data class ManagerRelease(val assets: List<ManagerAsset> = emptyList())

/**
 * Reads the fork's own releases straight from GitHub.
 *
 * The releases of this fork carry both the manager build and the corpus, so a manager release
 * newer than the installed one is where a newer corpus comes from. Only the tags and their assets
 * are read, not the repository API the bundle loader needs a token for.
 */
object OfflineUpdateCheck {
    private const val TAG = "LuckyCheck"
    private const val REPO = "rootedfor44/morphe-lp"
    private const val CORPORA = "lp-custom-patches.zip"

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether one of the newest manager releases of the fork is newer than [installed]. */
    suspend fun hasNewerManagerRelease(installed: String): Boolean {
        val tags = runCatching { releaseTags() }.getOrNull().orEmpty()
        for (tag in tags) {
            val release = tag.removePrefix("v")
            if (!LuckyBuildVersion.isNewer(release, installed)) continue
            if (hasCorpus(tag)) {
                Log.i(TAG, "Newer manager release with corpus: $tag, installed $installed")
                return true
            }
        }
        return false
    }

    private suspend fun releaseTags(): List<String> {
        val body = get(api("/tags?per_page=15")) ?: return emptyList()
        return json.decodeFromString<ManagerTags>(body)
            .tags
            .map { it.name }
            .filter { it.startsWith("v") }
            .sortedWith(
                compareByDescending<String> { versionKey(it.removePrefix("v")) }
                    .thenByDescending { LuckyBuildVersion.buildPart(it) ?: 0 }
            )
    }

    private fun versionKey(version: String): Long =
        LuckyBuildVersion.upstreamPart(version)
            .split('.')
            .mapNotNull { it.toIntOrNull() }
            .take(3)
            .fold(0L) { acc, part -> acc * 1000 + part }

    private suspend fun hasCorpus(tag: String): Boolean {
        val body = get(api("/releases/tags/$tag")) ?: return false
        return runCatching {
            json.decodeFromString<ManagerRelease>(body).assets.any { it.name == CORPORA }
        }.getOrDefault(false)
    }

    private fun api(path: String) = "https://api.github.com/repos/$REPO$path"

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return@withContext null
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url -> $code")
                return@withContext null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}