/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.lucky

import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.CompressionMethod
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.raw.HeaderItem
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile as DexFileIface
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.debug.DebugItem
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import java.io.File

/**
 * Generic Lucky Patcher-style actions, applied to DEX methods by name and signature.
 *
 * Each action is a module of its own, so the selection screen lets the user pick the ones an app
 * needs. Matching is by method name and, for the checks that only exist inside a particular kind
 * of class (billing, ads), by the class descriptor as well.
 *
 * These are client-side checks only. They cannot defeat a server that verifies an entitlement,
 * a receipt or an attestation it issued itself; they make the local check pass.
 */
object LuckyGenericActions {
    const val PREFIX = "action:"

    private class Action(
        val title: String,
        val description: String,
        /** Substrings of a lower-case method name that carry the check. */
        val keywords: List<String> = emptyList(),
        /** What a method whose name carries [keywords] is made to return. */
        val keywordResult: Boolean = true,
        val trueMethods: Set<String> = emptySet(),
        val falseMethods: Set<String> = emptySet(),
        /** Method names emptied out in classes whose descriptor holds the key. */
        val scopedVoid: Map<String, Set<String>> = emptyMap(),
        /** Boolean method names forced true in classes whose descriptor holds the key. */
        val scopedTrue: Map<String, Set<String>> = emptyMap()
    )

    /** Names that say the check failed, whichever module matched them. */
    private val negativeCues = listOf(
        "cancel", "expire", "invalid", "fail", "error", "denied", "refund", "pending",
        "missing", "unavailable", "required", "requires", "needs", "should", "must", "not"
    )

    private val actions = linkedMapOf(
        "license" to Action(
            title = "Remove license verification",
            description = "Makes local license and full-version checks pass. A license the " +
                "server validates on every run cannot be faked on the device.",
            keywords = listOf(
                "licen", "license", "fullversion", "fullversionunlocked", "activat"
            ),
            trueMethods = setOf(
                "islicensed", "islicensevalid", "checklicense", "verifylicense", "haslicense",
                "hasvalidlicense", "islicenseok", "isunlocked", "isfullversion", "isactivated",
                "ispro", "ispremium", "ispurchased", "issubscribed", "isvip", "ispaid"
            ),
            falseMethods = setOf(
                "istrial", "islicenseexpired", "islicenseinvalid", "islicenseexpiredornull",
                "isactivationrequired", "needslicense"
            )
        ),
        "premium" to Action(
            title = "Unlock premium features",
            description = "Turns local premium, pro, VIP and subscription flags on. Server-side " +
                "entitlement checks still decide what the account really owns.",
            keywords = listOf(
                "premium", "subscription", "subscribed", "unlock", "vip", "paid", "profeature"
            ),
            trueMethods = setOf(
                "ispremium", "ispro", "isvip", "isunlocked", "ispurchased", "issubscribed",
                "haspremium", "hassubscription", "ispremiumuser",
                "haspurchased", "issubscribeduser", "isfullversion", "ispaid"
            ),
            falseMethods = setOf(
                "istrial", "isfreeversion", "isliteversion", "ispaymentrequired",
                "issubscriptionexpired", "ispremiumexpired"
            )
        ),
        "iap" to Action(
            title = "Emulate in-app purchases",
            description = "Passes local purchase and billing checks: owned items, acknowledged " +
                "purchases and a ready billing client. A receipt the store validates on its " +
                "server is not covered.",
            keywords = listOf("purchase", "billing", "entitlement", "owned", "acknowledg"),
            trueMethods = setOf(
                "ispurchased", "haspurchased", "isowned", "isacknowledged", "isconsumed",
                "billingready", "isbillingready", "isbillingavailable", "issubscribed"
            ),
            falseMethods = setOf(
                "ispurchasecancelled", "ispurchasepending", "ispurchaserefunded",
                "ispurchaseexpired", "isbillingunavailable", "isbillingdisconnected"
            ),
            scopedTrue = mapOf(
                "billing" to setOf(
                    "isready", "isavailable", "issupported", "isfeaturesupported",
                    "isinitialized", "isconnected", "isbillingconnected"
                )
            )
        ),
        "ads" to Action(
            title = "Remove ads",
            description = "Turns local ad-free flags on, ad-required checks off, and empties ad " +
                "loading and showing calls inside the app's own ad classes.",
            keywords = listOf("adfree", "noads", "adsfree", "adsremoved", "adsdisabled"),
            trueMethods = setOf(
                "isadfree", "hasnoads", "adsremoved", "isadsremoved", "isadfreeuser",
                "hasremovedads", "isnoads", "areadsremoved"
            ),
            falseMethods = setOf(
                "shouldshowads", "areadsenabled", "isadsenabled", "showads", "adsenabled",
                "hasads", "isadsrequired", "shoulddisplayads", "canshowads", "isadrequired"
            ),
            scopedVoid = mapOf(
                "ads" to setOf(
                    "loadad", "loadads", "showad", "showads", "displayad", "displayads",
                    "requestad", "requestads", "loadbanner", "showbanner", "loadinterstitial",
                    "showinterstitial", "loadrewarded", "showrewarded", "loadnativead"
                ),
                "adview" to setOf("loadad", "showad", "loadads", "showads"),
                "admob" to setOf("loadad", "showad", "loadads", "showads"),
                "applovin" to setOf("loadad", "showad"),
                "unityads" to setOf("show", "load"),
                "ironsource" to setOf("loadad", "showad"),
                "mopub" to setOf("loadad", "showad")
            )
        ),
        "offline" to Action(
            title = "Allow offline use",
            description = "Makes connectivity and server-availability checks pass so the app " +
                "opens and keeps working without a network.",
            keywords = listOf(
                "online", "connected", "connectivity", "networkavail", "internetavail",
                "serveravail", "network", "internet"
            ),
            trueMethods = setOf(
                "isonline", "isnetworkavailable", "isconnected", "hasnetwork",
                "isserveravailable", "cansync", "hasinternet", "isinternetavailable",
                "hasconnectivity", "isnetworkconnected", "isinternetconnected"
            ),
            falseMethods = setOf(
                "requiresnetwork", "requiresinternet", "requiresonline", "needsinternet",
                "needsnetwork", "isnetworkrequired", "isserverrequired", "isnetworkmandatory",
                "isconnectionrequired"
            )
        ),
        "signature" to Action(
            title = "Bypass signature verification",
            description = "Makes local signature and certificate comparisons pass, for apps that " +
                "refuse a build signed with another key.",
            keywords = listOf("signature", "signing", "certificate", "certfingerprint"),
            trueMethods = setOf(
                "checksignature", "verifysignature", "issignaturevalid", "validatesignature",
                "signaturesmatch", "issignaturecorrect", "signatureequals", "checksignatures"
            ),
            falseMethods = setOf(
                "issignatureinvalid", "issignaturemismatch", "signaturesdiffer",
                "issignaturewrong", "signaturecheckfailed"
            )
        ),
        "root" to Action(
            title = "Hide root and emulator",
            description = "Turns root, emulator and debugger detection off. Only the app's own " +
                "checks are covered, not attestation a server performs.",
            keywords = listOf("root", "emulator", "emulated", "debugger", "debuggable", "magisk"),
            keywordResult = false,
            falseMethods = setOf(
                "isrooted", "isdevicerooted", "checkroot", "isrootavailable", "detectroot",
                "hasroot", "isemulator", "isrunningonemulator", "isemulated", "isdebuggerconnected",
                "isdebuggable", "isdebugmode", "issuavailable", "hasbusybox"
            ),
            trueMethods = setOf("isdevicesecure")
        ),
        "integrity" to Action(
            title = "Bypass integrity checks",
            description = "Makes local Play Integrity and SafetyNet results pass. Attestation the " +
                "server validates with Google cannot be forged on the device.",
            keywords = listOf("integrity", "safetynet", "attest", "playprotect", "deviceverification"),
            trueMethods = setOf(
                "isintegrityvalid", "integritypassed", "issafetynetpassed", "isattestationvalid",
                "isdeviceverified", "isplayintegrityavailable", "isintegrityavailable"
            ),
            falseMethods = setOf(
                "hasintegrityerror", "integrityfailed", "isintegritycompromised",
                "issafetynetfailed", "attestationfailed"
            )
        ),
        "vpn" to Action(
            title = "Hide VPN and proxy",
            description = "Turns VPN and proxy detection off, for apps that restrict their " +
                "features to direct connections.",
            keywords = listOf("vpn", "proxy"),
            keywordResult = false,
            falseMethods = setOf(
                "isvpnactive", "isvpnconnected", "isusingvpn",
                "isproxyenabled", "isusingproxy", "detectvpn", "detectproxy", "hasvpn"
            )
        ),
        "tamper" to Action(
            title = "Hide hooks and tampering",
            description = "Turns tamper, hook, Xposed and Frida detection off, for apps that " +
                "refuse to run on a modified device.",
            keywords = listOf("tamper", "hook", "xposed", "frida", "substrate", "injection"),
            keywordResult = false,
            falseMethods = setOf(
                "istampered", "ishooked", "isxposeddetected", "isfridadetected", "detecthook",
                "ishookdetected", "isxposedinstalled", "isfridarunning", "istamperdetected"
            )
        )
    )

    /** Class prefixes whose code is library plumbing rather than the app's own checks. */
    private val ignoredClassPrefixes = listOf(
        "Landroid/", "Landroidx/", "Ljava/", "Ljavax/", "Lkotlin/", "Lkotlinx/", "Ldalvik/",
        "Lcom/google/android/material/", "Lcom/google/android/gms/"
    )

    /** `(id, title, description)` of every action, for the patch bundle listing. */
    val patchInfo: List<Triple<String, String, String>>
        get() = actions.map { (id, action) -> Triple("$PREFIX$id", action.title, action.description) }

    fun apply(apk: File, selected: List<String>, logger: (String) -> Unit): Int {
        val chosen = selected
            .mapNotNull { id -> actions[id.removePrefix(PREFIX)] }
            .distinct()
        if (chosen.isEmpty()) return 0

        val options = ZFileOptions().setAlignmentRule(
            AlignmentRules.compose(
                AlignmentRules.constantForSuffix(".so", 16384),
                AlignmentRules.constant(4)
            )
        )

        var changes = 0
        ZFile.openReadWrite(apk, options).use { zip ->
            val dexEntries = zip.entries().filter { entry ->
                dexRegex.matches(entry.centralDirectoryHeader.name)
            }
            for (entry in dexEntries) {
                val name = entry.centralDirectoryHeader.name
                val bytes = entry.read()
                val temp = File.createTempFile("lucky-", ".dex")
                val out = File.createTempFile("lucky-out-", ".dex")
                try {
                    temp.writeBytes(bytes)
                    val dex = DexFileFactory.loadDexFile(temp, opcodesFor(bytes))
                    val rewritten = rewrite(dex, chosen) ?: continue
                    changes += rewritten.second
                    if (rewritten.second == 0) continue

                    DexFileFactory.writeDexFile(out.absolutePath, rewritten.first)
                    val deflated =
                        entry.centralDirectoryHeader.compressionInfoWithWait.method ==
                            CompressionMethod.DEFLATE
                    zip.add(name, out.inputStream(), deflated)
                    logger("Lucky Patcher: $name: ${rewritten.second} method(s) rewritten")
                } catch (e: Exception) {
                    // A DEX the writer cannot round-trip is left as it is rather than breaking
                    // the whole run: the other actions still apply.
                    logger("Lucky Patcher: $name skipped: ${e.message}")
                } finally {
                    temp.delete()
                    out.delete()
                }
            }
            zip.finishAllBackgroundTasks()
            zip.realign()
        }
        return changes
    }

    /** The opcodes of the DEX itself, so a rewrite keeps its version and instruction set. */
    private fun opcodesFor(bytes: ByteArray): Opcodes {
        val version = runCatching { HeaderItem.getVersion(bytes, 0) }.getOrNull() ?: -1
        return runCatching { Opcodes.forDexVersion(version) }.getOrNull() ?: Opcodes.getDefault()
    }

    /** Returns the rewritten DEX and how many method bodies changed, or null when nothing did. */
    private fun rewrite(
        dex: DexFileIface,
        chosen: List<Action>
    ): Pair<DexFileIface, Int>? {
        var changed = 0
        val classes = dex.classes.map { classDef ->
            if (ignoredClassPrefixes.any { classDef.type.startsWith(it) }) return@map classDef

            val descriptor = classDef.type.lowercase()
            var classChanged = false

            fun patch(method: Method): Method {
                if (method.implementation == null) return method
                val name = method.name.lowercase()
                return when (method.returnType) {
                    "Z" -> {
                        val value = booleanValue(name, descriptor, chosen) ?: return method
                        classChanged = true
                        changed++
                        replaceBody(classDef, method, value)
                    }
                    "V" -> {
                        if (!isScopedVoid(name, descriptor, chosen)) return method
                        classChanged = true
                        changed++
                        emptyBody(classDef, method)
                    }
                    else -> method
                }
            }

            // Direct and virtual methods are both built, as the DEX writer reads them rather than
            // the combined set, and the combined set stays consistent with them.
            val direct = classDef.directMethods.map(::patch)
            val virtual = classDef.virtualMethods.map(::patch)
            if (!classChanged) {
                classDef
            } else {
                object : ClassDef by classDef {
                    override fun getDirectMethods(): MutableSet<Method> = direct.toMutableSet()
                    override fun getVirtualMethods(): MutableSet<Method> = virtual.toMutableSet()
                    override fun getMethods(): MutableSet<Method> = (direct + virtual).toMutableSet()
                }
            }
        }
        if (changed == 0) return null
        val rewritten = object : DexFileIface {
            override fun getClasses(): Set<ClassDef> = classes.toSet()
            override fun getOpcodes(): Opcodes = dex.opcodes
        }
        return rewritten to changed
    }

    /** What a boolean method is made to return, or null when no selected action matches it. */
    private fun booleanValue(name: String, descriptor: String, chosen: List<Action>): Boolean? {
        chosen.forEach { action ->
            if (name in action.falseMethods) return false
            if (name in action.trueMethods) return true
        }

        chosen.forEach { action ->
            action.scopedTrue.forEach { (hint, names) ->
                if (descriptor.contains(hint) && name in names) return true
            }
        }

        chosen.forEach { action ->
            if (action.keywords.any { name.contains(it) }) {
                if (negativeCues.any { name.contains(it) }) return false
                return action.keywordResult
            }
        }
        return null
    }

    private fun isScopedVoid(name: String, descriptor: String, chosen: List<Action>): Boolean =
        chosen.any { action ->
            action.scopedVoid.any { (hint, names) ->
                descriptor.contains(hint) && name in names
            }
        }

    private fun replaceBody(classDef: ClassDef, method: Method, value: Boolean): Method {
        val body = ImmutableMethodImplementation(
            // The original count keeps registers_size big enough for the incoming parameters
            method.implementation?.registerCount ?: 2,
            listOf<Instruction>(
                ImmutableInstruction11n(Opcode.CONST_4, 0, if (value) 1 else 0),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            ),
            emptyList<TryBlock<ExceptionHandler>>(),
            emptyList<DebugItem>()
        )
        return ImmutableMethod(
            classDef.type,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            body
        )
    }

    private fun emptyBody(classDef: ClassDef, method: Method): Method {
        val body = ImmutableMethodImplementation(
            method.implementation?.registerCount ?: 0,
            listOf<Instruction>(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            emptyList<TryBlock<ExceptionHandler>>(),
            emptyList<DebugItem>()
        )
        return ImmutableMethod(
            classDef.type,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            body
        )
    }

    private val dexRegex = Regex("""classes\d*\.dex""")
}
