/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.lucky

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.morphe.manager.BuildConfig
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.network.service.AssetDownloader
import app.morphe.manager.patcher.lucky.LUCKY_BUNDLE_UID
import app.morphe.manager.patcher.lucky.LuckyGenericActions
import app.morphe.manager.patcher.patch.CompatiblePackage
import app.morphe.manager.patcher.patch.PatchBundleInfo
import app.morphe.manager.patcher.patch.PatchInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** A custom patch file from the bundled Lucky Patcher corpus. */
data class LuckyRule(
    val fileName: String,
    val title: String,
    val description: String,
    val packages: Set<String>
)

/**
 * The Lucky Patcher custom patches.
 *
 * The corpus is shipped as a zip under `assets` and can be refreshed from the manager's releases.
 * It is extracted into the app's files and parsed into [LuckyRule]s. The parsed rules are presented
 * to the manager as one more patch source, so the normal selection screen can offer Lucky Patcher
 * patches next to Morphe patches.
 *
 * Updates ride the manager's existing source update triggers: [updateIfStale] is called wherever
 * the patch bundles are checked, and the bundled copy stays as the offline fallback.
 */
class LuckyRepository(
    private val context: Context,
    private val downloader: AssetDownloader,
    private val prefs: PreferencesManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rulesDir = File(context.filesDir, RULES_DIR)

    private val _rules = MutableStateFlow<List<LuckyRule>>(emptyList())
    val rules: StateFlow<List<LuckyRule>> = _rules.asStateFlow()

    @Volatile
    private var loaded = false

    init {
        scope.launch { ensureLoaded() }
    }

    /** Every patch of the source and the generic actions, as one patch bundle. */
    val actionCount: Int get() = LuckyGenericActions.patchInfo.size

    /** Extracts and parses the corpus if it is not there yet. Safe to call repeatedly. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val bundledVersion = File(rulesDir, BUNDLED_FILE)
                val needsExtract = !bundledVersion.isFile ||
                    bundledVersion.readText() != bundledVersionKey()
                if (needsExtract) extract()
                _rules.value = parseAll()
                loaded = true
                Log.i(TAG, "Loaded ${_rules.value.size} Lucky Patcher rules from $rulesDir")
            }.onFailure {
                Log.e(TAG, "Could not load Lucky Patcher rules", it)
            }
        }
    }

    /**
     * Refreshes the corpus from the fork's own releases when the last check is old enough, or
     * unconditionally with [force]. Failures leave the current corpus in place.
     *
     * The corpus is shipped with every manager release of the fork, so this rides the manager
     * update path rather than a separate source: a newer manager release is what carries a newer
     * corpus. An upstream-only release does not, and then the shipped corpus stays.
     *
     * @param allowUnsafeNetwork Download even on a metered network, for a check the user asked
     *   for explicitly.
     */
    suspend fun updateIfStale(allowUnsafeNetwork: Boolean = false, force: Boolean = false) {
        withContext(Dispatchers.IO) { ensureLoaded() }
        if (!force && !isStale()) return
        if (!allowUnsafeNetwork && !prefs.allowMeteredUpdates.get() && isMetered()) {
            Log.i(TAG, "Skipping Lucky Patcher update on a metered network")
            return
        }
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isManagerNewer()) {
                    // The shipped corpus is the newest this build can carry until the manager
                    // itself is updated, so it is put back rather than a stale download kept.
                    restoreBundled()
                } else {
                    downloadAndInstall()
                }
            }.onFailure { Log.w(TAG, "Lucky Patcher update failed, keeping the current corpus", it) }
            // Recorded either way, so a failing release is not retried on every launch.
            writeLastCheck()
        }
    }

    /**
     * Whether a manager release newer than the one installed is out, which is where a newer corpus
     * comes from. Asked against the same releases the corpus is downloaded from.
     */
    suspend fun isManagerNewer(): Boolean =
        OfflineUpdateCheck.hasNewerManagerRelease(BuildConfig.VERSION_NAME)

    private fun restoreBundled() {
        rulesDir.deleteRecursively()
        rulesDir.mkdirs()
        context.assets.open(BUNDLED_ASSET).use { input -> unzip(input, rulesDir) }
        File(rulesDir, BUNDLED_FILE).writeText(bundledVersionKey())
        File(rulesDir, SOURCE_FILE).writeText(SOURCE_BUNDLED)
        _rules.value = parseAll()
    }

    fun rulesForPackage(packageName: String): List<LuckyRule> =
        _rules.value.filter { rule ->
            rule.packages.any { it.equals(packageName, ignoreCase = true) } ||
                rule.fileName.contains(packageName, ignoreCase = true)
        }

    /** Every package any rule names, for the home list and the LP filters. */
    fun packages(): Set<String> = _rules.value.flatMapTo(mutableSetOf()) { it.packages }

    /**
     * The Lucky Patcher source as a normal patch bundle. Patches are compatible with exactly the
     * packages their rule names, so Morphe's own selection screen filters them per app.
     */
    fun bundleInfo(): PatchBundleInfo.Global {
        val patches = _rules.value.map { rule ->
            PatchInfo(
                name = rule.fileName,
                description = rule.description.ifBlank { "Lucky Patcher custom patch" },
                include = true,
                compatiblePackages = persistentListOf(
                    CompatiblePackage(
                        packageName = rule.packages.firstOrNull(),
                        versions = null
                    )
                ),
                options = null,
                displayName = rule.title.ifBlank { rule.fileName },
                category = "Lucky Patcher"
            )
        } + LuckyGenericActions.patchInfo.map { (id, title, description) ->
            // Generic actions are universal: they match the methods they name in whatever APK
            // they run on, so no package list is declared for them.
            PatchInfo(
                name = id,
                description = description,
                include = false,
                compatiblePackages = null,
                options = null,
                displayName = title,
                category = "Lucky Patcher"
            )
        }
        return PatchBundleInfo.Global(
            name = BUNDLE_NAME,
            version = corpusVersion(),
            uid = LUCKY_BUNDLE_UID,
            enabled = true,
            patches = patches,
            patcherVersion = null
        )
    }

    /** The version the listing shows: the shipped one, marked when the corpus came from a release. */
    private fun corpusVersion(): String {
        val remote = File(rulesDir, SOURCE_FILE).takeIf { it.isFile }?.readText() == SOURCE_REMOTE
        return if (remote) "$BUNDLED_VERSION+remote" else BUNDLED_VERSION
    }

    private suspend fun downloadAndInstall() {
        val staging = File(context.cacheDir, "lp-patches-staging")
        val zip = File(context.cacheDir, "lp-custom-patches-update.zip")
        try {
            Log.i(TAG, "Downloading Lucky Patcher patches from $REMOTE_URL")
            downloader.downloadToFile(REMOTE_URL, zip)

            staging.deleteRecursively()
            staging.mkdirs()
            zip.inputStream().use { input -> unzip(input, staging) }

            val found = staging.listFiles { file -> file.isFile && file.extension.equals("txt", true) }
                ?.size ?: 0
            require(found > 0) { "The downloaded archive holds no patch files" }

            rulesDir.deleteRecursively()
            rulesDir.mkdirs()
            staging.listFiles()?.forEach { file ->
                file.copyTo(File(rulesDir, file.name), overwrite = true)
            }
            File(rulesDir, BUNDLED_FILE).writeText(bundledVersionKey())
            File(rulesDir, SOURCE_FILE).writeText(SOURCE_REMOTE)

            _rules.value = parseAll()
            loaded = true
            Log.i(TAG, "Updated $found Lucky Patcher rules from $REMOTE_URL")
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun writeLastCheck() {
        runCatching {
            File(rulesDir, LAST_CHECK_FILE).writeText(System.currentTimeMillis().toString())
        }
    }

    private fun isStale(): Boolean {
        val last = File(rulesDir, LAST_CHECK_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.toLongOrNull() ?: 0L
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun isMetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.isActiveNetworkMetered
    }

    private fun extract() {
        if (rulesDir.exists()) rulesDir.deleteRecursively()
        rulesDir.mkdirs()
        context.assets.open(BUNDLED_ASSET).use { input ->
            unzip(input, rulesDir)
        }
        File(rulesDir, BUNDLED_FILE).writeText(bundledVersionKey())
        File(rulesDir, SOURCE_FILE).writeText(SOURCE_BUNDLED)
    }

    /**
     * What the bundled corpus was extracted as. A build that ships a newer corpus ships the asset
     * changed too, so the manager version in it is enough to tell the corpora apart, and a shipped
     * corpus is preferred to one a release put there.
     */
    private fun bundledVersionKey(): String =
        "$BUNDLED_VERSION/${BuildConfig.VERSION_NAME}"

    private fun unzip(input: java.io.InputStream, target: File) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                if (!entry.isDirectory && name.isNotBlank()) {
                    File(target, name).outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun parseAll(): List<LuckyRule> {
        val files = rulesDir.listFiles { file -> file.isFile && file.extension.equals("txt", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        return files.mapNotNull { file ->
            runCatching { parse(file) }
                .onFailure { Log.w(TAG, "Could not parse ${file.name}", it) }
                .getOrNull()
        }
    }

    private fun parse(file: File): LuckyRule {
        val text = file.readText()
        val title = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("{") }
            .orEmpty()
        val declared = sectionValues(text, "PACKAGE")
            .flatMap { it.split(',', ' ', ';') }
            .map { it.trim() }
            .filter { it.matches(PACKAGE_REGEX) }
        val packages = if (declared.isNotEmpty()) declared.toSet() else derivePackages(file.name)
        val description = text.lineSequence()
            .map { it.trim() }
            .dropWhile { it != title }
            .drop(1)
            .firstOrNull { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("{") }
            .orEmpty()
        return LuckyRule(
            fileName = file.name,
            title = title,
            description = description,
            packages = packages
        )
    }

    private fun sectionValues(text: String, section: String): List<String> {
        var inside = false
        val values = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                inside = line.equals("[$section]", ignoreCase = true)
                return@forEach
            }
            if (inside && line.isNotBlank() && !line.startsWith("{") && !line.startsWith("#")) {
                values += line
            }
        }
        return values
    }

    /**
     * Rule files are named after their target, e.g. `160dpi.com.google.android.gsf.txt` or
     * `arun483.mega_com.cmplay.tiles2.txt`. Take the suffix that starts at a known top-level label.
     */
    private fun derivePackages(fileName: String): Set<String> {
        val base = fileName.substringBeforeLast('.').replace('_', '.').replace('-', '.')
        val tokens = base.split('.')
        val start = tokens.indexOfFirst { it.lowercase() in TOP_LEVEL_LABELS }
        if (start < 0 || start == tokens.lastIndex) return emptySet()
        val candidate = tokens.drop(start).joinToString(".").trim('.')
        return if (candidate.count { it == '.' } >= 1) setOf(candidate) else emptySet()
    }

    companion object {
        private const val TAG = "LuckyRepository"
        const val BUNDLED_ASSET = "lp-custom-patches.zip"
        const val BUNDLED_VERSION = "2026.11.06"
        const val BUNDLE_NAME = "Lucky Patcher"
        const val RULES_DIR = "lp-patches"

        /** Release asset the corpus is refreshed from, through the same downloader as bundles. */
        const val REMOTE_URL =
            "https://github.com/rootedfor44/morphe-lp/releases/latest/download/lp-custom-patches.zip"

        private const val BUNDLED_FILE = ".bundled"
        private const val SOURCE_FILE = ".source"
        private const val LAST_CHECK_FILE = ".lastCheck"
        private const val MANAGER_FILE = ".managerVersion"
        private const val SOURCE_BUNDLED = "bundled"
        private const val SOURCE_REMOTE = "remote"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val TOP_LEVEL_LABELS = setOf(
            "com", "org", "net", "io", "app", "dev", "me", "co",
            "ru", "pl", "de", "fr", "uk", "us", "eu", "cz", "sk", "hu", "ro", "bg", "ua", "by", "kz",
            "it", "es", "pt", "nl", "be", "se", "no", "dk", "fi", "ch", "at", "gr", "tr", "il",
            "cn", "jp", "kr", "in", "id", "th", "vn", "my", "sg", "ph", "hk", "tw",
            "br", "mx", "ar", "cl", "pe", "ve", "za", "eg", "ae", "sa"
        )
    }
}
