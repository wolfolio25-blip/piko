/*
 * Backported from Piko's XChat client-version spoof fix (8d265d8).
 * Adapted for Morphe patcher 1.14.1 so the user's custom
 * bookmarks, timeline UI, and other Vantage changes stay intact.
 */
package app.crimera.patches.twitter.misc.clientversion

import app.crimera.patches.twitter.misc.extension.sharedExtensionPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "$PATCHES_DESCRIPTOR/clientversion/SpoofClientVersionPatch;"
private const val REPORTED_VERSION = "12.44.0-prod.01"

// Enumerate XChat GraphQL operation names, as some request paths contain only "graphql".
private object GraphQLOperationsFingerprint : Fingerprint(
    definingClass = "Lcom/x/android/xchat/",
    name = "name",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

// The legacy XChat request headers include both User-Agent and X-Twitter-Client-Version.
private object LegacyClientHeaderBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/twitter/network/",
    returnType = "V",
    parameters = listOf("Ljava/net/URI;", "Lcom/twitter/util/user/UserIdentifier;", "Lcom/twitter/network/"),
    filters = listOf(
        string("User-Agent"),
        methodCall(name = "toString", location = InstructionLocation.MatchAfterWithin(2)),
        string("X-Twitter-Client-Version"),
        string("12.19.1-release.0", location = InstructionLocation.MatchAfterWithin(2)),
    ),
)

@Suppress("unused")
val spoofClientVersionPatch =
    bytecodePatch(
        name = "Spoof client version (XChat)",
        description = "Fixes XChat 'Update Required' by reporting a newer app version for XChat requests only.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(sharedExtensionPatch)

        execute {
            // Inject operation names into the bundled extension's empty placeholder method.
            val operations = sortedSetOf<String>()
            val operationRegex = Regex("[A-Za-z]+")
            GraphQLOperationsFingerprint.matchAll().forEach { match ->
                val method = match.method
                val first = method.implementation?.instructions?.firstOrNull()
                    ?: throw PatchException("Empty XChat GraphQL operation method")
                val operation = ((first as ReferenceInstruction).reference as StringReference).string
                if (!operation.matches(operationRegex)) {
                    throw PatchException("Unexpected XChat operation name: $operation")
                }
                operations.add(operation)
            }
            if ("SendMessageCreateMutation" !in operations) {
                throw PatchException("XChat GraphQL operations not found ($operations)")
            }
            val operationList = operations.joinToString(",")
            Fingerprint(definingClass = EXTENSION_CLASS_DESCRIPTOR, name = "xchatOperations")
                .method.addInstructions(
                    0,
                    """
                    const-string v0, "$operationList"
                    return-object v0
                    """.trimIndent(),
                )

            LegacyClientHeaderBuilderFingerprint.let { fingerprint ->
                fingerprint.method.apply {
                    // Insert from last matching instruction towards first to keep indexes stable.
                    val versionIndex = fingerprint.instructionMatches.last().index
                    val isXChatRegister = findFreeRegister(versionIndex + 1)
                    val versionRegister = fingerprint.instructionMatches.last()
                        .getInstruction<OneRegisterInstruction>().registerA

                    addInstructionsWithLabels(
                        versionIndex + 1,
                        """
                        if-eqz v$isXChatRegister, :keep_original_xchat_version
                        const-string v$versionRegister, "$REPORTED_VERSION"
                        :keep_original_xchat_version
                        nop
                        """.trimIndent(),
                    )

                    val agentResultIndex = fingerprint.instructionMatches[1].index + 1
                    val agentRegister = getInstruction<OneRegisterInstruction>(agentResultIndex).registerA
                    addInstructionsWithLabels(
                        agentResultIndex + 1,
                        """
                        if-eqz v$isXChatRegister, :keep_original_xchat_agent
                        invoke-static {v$agentRegister}, $EXTENSION_CLASS_DESCRIPTOR->userAgent(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$agentRegister
                        :keep_original_xchat_agent
                        nop
                        """.trimIndent(),
                    )

                    addInstructions(
                        0,
                        """
                        invoke-static {p1}, $EXTENSION_CLASS_DESCRIPTOR->isXChatRequest(Ljava/net/URI;)Z
                        move-result v$isXChatRegister
                        """.trimIndent(),
                    )
                }
            }
        }
    }
