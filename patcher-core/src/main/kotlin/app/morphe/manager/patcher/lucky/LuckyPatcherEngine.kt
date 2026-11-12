/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.lucky

import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import java.io.File

/** Reserved bundle id of the inbuilt Lucky Patcher source. */
const val LUCKY_BUNDLE_UID: Int = Int.MAX_VALUE - 7

/** Folder the Lucky Patcher custom-patch files are extracted to. */
const val LUCKY_PATCHES_DIR: String = "lp-patches"

/**
 * Applies Lucky Patcher custom-patch files to an APK without running Lucky Patcher.
 *
 * The supported dialect covers byte patches in DEX files, native libraries and arbitrary APK
 * entries, plus adding, deleting and renaming APK entries. Sections that need root, Xposed or
 * Lucky Patcher's own runtime are ignored rather than failing the whole run.
 */
object LuckyPatcherEngine {
    private val headerRegex = Regex("""^\[([A-Za-z0-9_ -]+)]\s*$""")
    private val fieldRegex = Regex("""\{\s*"([^"]+)"\s*:\s*"([^"]*)"\s*}""")
    private val dexRegex = Regex("""classes(\d*)\.dex""")
    private val signatureRegex = Regex("""META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]*)""", RegexOption.IGNORE_CASE)

    /** One search/replace pair, with `??`/`**` matching any byte. */
    private class BytePatch(
        val original: ByteArray,
        val wildcard: BooleanArray,
        val replacement: ByteArray
    )

    private class Rule(
        val classPatches: MutableList<BytePatch> = mutableListOf(),
        val libraryPatches: MutableMap<String, MutableList<BytePatch>> = linkedMapOf(),
        val filePatches: MutableMap<String, MutableList<BytePatch>> = linkedMapOf(),
        val patchAll: MutableList<BytePatch> = mutableListOf(),
        val addFiles: MutableMap<String, ByteArray> = linkedMapOf(),
        val deleteFiles: MutableSet<String> = linkedSetOf(),
        val deleteFolders: MutableSet<String> = linkedSetOf(),
        val renameFiles: MutableMap<String, String> = linkedMapOf()
    )

    fun apply(
        input: File,
        output: File,
        rulesDir: File,
        ruleNames: List<String>,
        logger: (String) -> Unit
    ): Int {
        input.copyTo(output, overwrite = true)

        val rules = ruleNames.filterNot { it.startsWith(LuckyGenericActions.PREFIX) }.mapNotNull { name ->
            val file = File(rulesDir, name)
            if (!file.isFile) {
                logger("Lucky Patcher: rule not found: $name")
                null
            } else {
                runCatching { parse(file.readText()) }
                    .onFailure { logger("Lucky Patcher: cannot parse $name: ${it.message}") }
                    .getOrNull()
            }
        }
        val actions = ruleNames.filter { it.startsWith(LuckyGenericActions.PREFIX) }
        if (rules.isEmpty()) return LuckyGenericActions.apply(output, actions, logger)

        var changes = 0
        val options = ZFileOptions().setAlignmentRule(
            AlignmentRules.compose(
                AlignmentRules.constantForSuffix(".so", 16384),
                AlignmentRules.constant(4)
            )
        )

        ZFile.openReadWrite(output, options).use { zip ->
            val names = zip.entries().map { it.centralDirectoryHeader.name }
            val delete = rules.flatMapTo(linkedSetOf()) { it.deleteFiles }
            val deleteFolders = rules.flatMapTo(linkedSetOf()) { it.deleteFolders }
            val renames = linkedMapOf<String, String>()
            rules.forEach { renames.putAll(it.renameFiles) }
            val additions = linkedMapOf<String, ByteArray>()
            rules.forEach { additions.putAll(it.addFiles) }

            // Removals first, so a rule can delete an entry and add a replacement.
            names.filter { name ->
                name in delete || deleteFolders.any { folder ->
                    name.startsWith(folder.trimEnd('/') + "/")
                }
            }.forEach { name ->
                zip.entries().firstOrNull { it.centralDirectoryHeader.name == name }?.delete()
                changes++
            }

            names.forEach { name ->
                if (name in delete || deleteFolders.any { name.startsWith(it.trimEnd('/') + "/") }) return@forEach

                val entry = zip.entries().firstOrNull { it.centralDirectoryHeader.name == name } ?: return@forEach
                val isDex = dexRegex.matches(name.substringAfterLast('/'))
                val isSignature = name.matches(signatureRegex)
                if (isSignature) {
                    entry.delete()
                    changes++
                    return@forEach
                }

                val patches = buildList {
                    if (isDex) rules.forEach { addAll(it.classPatches) }
                    rules.forEach { rule ->
                        rule.libraryPatches[name]?.let(::addAll)
                        rule.filePatches[name]?.let(::addAll)
                        addAll(rule.patchAll)
                    }
                }
                if (patches.isEmpty()) return@forEach

                val original = entry.read()
                var patched = original
                var touched = false
                patches.forEach { patch ->
                    val result = applyPatch(patched, patch)
                    if (result.second > 0) {
                        patched = result.first
                        touched = true
                        changes += result.second
                    }
                }
                if (!touched) return@forEach

                val deflated = entry.centralDirectoryHeader.compressionInfoWithWait.method == CompressionMethod.DEFLATE
                zip.add(name, patched.inputStream(), deflated)
            }

            renames.forEach { (from, to) ->
                val entry = zip.entries().firstOrNull { it.centralDirectoryHeader.name == from }
                if (entry != null) {
                    val bytes = entry.read()
                    entry.delete()
                    zip.add(to, bytes.inputStream(), true)
                    changes++
                }
            }

            additions.forEach { (name, bytes) ->
                zip.add(name, bytes.inputStream(), !name.endsWith(".so"))
                changes++
            }

            zip.finishAllBackgroundTasks()
            zip.realign()
        }

        if (actions.isNotEmpty()) {
            changes += LuckyGenericActions.apply(output, actions, logger)
        }

        logger("Lucky Patcher: applied ${ruleNames.size} rule(s), $changes change(s)")
        return changes
    }

    private fun parse(text: String): Rule {
        val rule = Rule()
        var section = ""
        var target = ""
        var pendingOriginal: ByteArray? = null
        var pendingWildcard: BooleanArray? = null

        fun commit(replacement: String) {
            val original = pendingOriginal ?: return
            val wildcard = pendingWildcard ?: return
            val replaced = decode(replacement).first
            if (replaced.size != original.size) {
                pendingOriginal = null
                pendingWildcard = null
                return
            }
            val patch = BytePatch(original, wildcard, replaced)
            when {
                section == "CLASSES" -> rule.classPatches += patch
                section == "PATCH ALL FILES" -> rule.patchAll += patch
                section.startsWith("LIB") && target.isNotEmpty() ->
                    rule.libraryPatches.getOrPut(target) { mutableListOf() } += patch
                section == "FILE_IN_APK" && target.isNotEmpty() ->
                    rule.filePatches.getOrPut(target) { mutableListOf() } += patch
            }
            pendingOriginal = null
            pendingWildcard = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            headerRegex.matchEntire(line)?.let {
                section = it.groupValues[1].uppercase()
                target = ""
                pendingOriginal = null
                pendingWildcard = null
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            fieldRegex.findAll(line).forEach { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                when (key) {
                    "name" -> target = value
                    "original" -> {
                        val (bytes, wildcard) = decode(value)
                        pendingOriginal = bytes
                        pendingWildcard = wildcard
                    }
                    "replaced" -> commit(value)
                    "from" -> target = value
                    "to" -> if (section == "RENAME_FILE_IN_APK") rule.renameFiles[target] = value
                    "file" -> if (section == "ADD_FILE_IN_APK") {
                        // Contents come as hex in the next "original" field.
                        target = value
                    }
                }
            }

            if (section == "ADD_FILE_IN_APK" && target.isNotEmpty() && line.startsWith("{")) {
                // Handled through the original/replaced fields below.
            }

            // A bare hex payload in an add-file section is the file contents.
            if (section == "ADD_FILE_IN_APK" && target.isNotEmpty() &&
                line.none { it == '{' } && line.matches(Regex("""[0-9A-Fa-f?* ]+"""))
            ) {
                rule.addFiles[target] = decode(line).first
            }
        }
        return rule
    }

    /** Decodes hex bytes; `??` and `**` become zero bytes flagged as wildcards. */
    private fun decode(value: String): Pair<ByteArray, BooleanArray> {
        val tokens = value.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val bytes = ByteArray(tokens.size)
        val wildcard = BooleanArray(tokens.size)
        tokens.forEachIndexed { index, token ->
            if (token == "??" || token == "**") {
                wildcard[index] = true
            } else {
                bytes[index] = token.toInt(16).toByte()
            }
        }
        return bytes to wildcard
    }

    private fun applyPatch(data: ByteArray, patch: BytePatch): Pair<ByteArray, Int> {
        if (patch.original.isEmpty() || data.size < patch.original.size) return data to 0
        var output: ByteArray? = null
        var matches = 0
        var offset = 0
        while (offset <= data.size - patch.original.size) {
            var matched = true
            for (i in patch.original.indices) {
                if (!patch.wildcard[i] && data[offset + i] != patch.original[i]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val target = output ?: data.copyOf().also { output = it }
                for (i in patch.replacement.indices) {
                    if (!patch.wildcard[i]) target[offset + i] = patch.replacement[i]
                }
                matches++
                offset += patch.original.size
            } else {
                offset++
            }
        }
        return (output ?: data) to matches
    }
}
