package com.yet.pets.core

import kotlinx.serialization.json.*

public sealed interface CodexV2ParseOutcome {
    public data class Success(public val definition: PetDefinition, public val spritesheetPath: String) : CodexV2ParseOutcome
    public data class Failure(public val report: CodexV2Report) : CodexV2ParseOutcome
}

/** Pure manifest path resolution; confinement remains the IO layer's job. */
public sealed interface CodexV2SpritesheetPathOutcome {
    public data class Success(public val path: String) : CodexV2SpritesheetPathOutcome
    public data class Failure(public val error: CodexV2Error) : CodexV2SpritesheetPathOutcome
}

/**
 * Explicit bounded V2 adapter; never auto-detected by the V1 parser. Requires
 * literal JSON integer 2 for spriteVersionNumber. The library rejects frame
 * and animations members (including null), ignores other unknown fields,
 * and uses the existing identity/path defaults. PNG/WebP must be static.
 * Inspection validates bounded container metadata, not decoded pixels or alpha.
 */
public object CodexV2PetPackageParser {
    private val json = Json
    private class Manifest(val id: String?, val displayName: String?, val description: String?, val path: String)
    private class Invalid(val error: CodexV2Error) : IllegalArgumentException(error.message)

    private fun limit(size: Int): CodexV2Error = CodexV2Error.InputLimitExceeded(
        "manifest is $size bytes; maximum is ${PetInputLimits.MAX_MANIFEST_BYTES}",
    )
    private fun failure(error: CodexV2Error): CodexV2ParseOutcome = CodexV2ParseOutcome.Failure(CodexV2Report(listOf(error)))

    private fun read(text: String): Manifest {
        if (text.length > PetInputLimits.MAX_MANIFEST_BYTES) throw Invalid(limit(text.length))
        val size = text.encodeToByteArray().size
        if (size > PetInputLimits.MAX_MANIFEST_BYTES) throw Invalid(limit(size))
        val obj = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("manifest must be a JSON object")
        val version = obj["spriteVersionNumber"] as? JsonPrimitive
        if (version == null || version.isString || version.content != "2") {
            throw Invalid(CodexV2Error.UnsupportedVersion("spriteVersionNumber must be literal JSON integer 2"))
        }
        for (member in listOf("frame", "animations")) {
            if (member in obj) throw Invalid(CodexV2Error.UnsupportedOverride(member))
        }
        fun string(name: String): String? {
            val value = obj[name] ?: return null
            if (value === JsonNull) return null
            if (value !is JsonPrimitive || !value.isString) throw IllegalArgumentException("$name must be a string or null")
            return value.content
        }
        return Manifest(string("id"), string("displayName"), string("description"),
            string("spritesheetPath")?.trim()?.ifEmpty { null } ?: CodexV2.DEFAULT_SPRITESHEET_PATH)
    }
    private fun decode(bytes: ByteArray): String {
        if (bytes.size > PetInputLimits.MAX_MANIFEST_BYTES) throw Invalid(limit(bytes.size))
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
    private fun error(e: Exception): CodexV2Error =
        if (e is Invalid) e.error else CodexV2Error.MalformedManifest("invalid Codex V2 manifest: ${e.message}")

    public fun spritesheetPathOf(manifestJson: String): CodexV2SpritesheetPathOutcome = try {
        CodexV2SpritesheetPathOutcome.Success(read(manifestJson).path)
    } catch (e: Exception) { CodexV2SpritesheetPathOutcome.Failure(error(e)) }

    public fun spritesheetPathOf(manifestBytes: ByteArray): CodexV2SpritesheetPathOutcome = try {
        spritesheetPathOf(decode(manifestBytes))
    } catch (e: Exception) { CodexV2SpritesheetPathOutcome.Failure(error(e)) }

    /** Bounded raw pair; metadata inspection does not decode pixels. */
    public fun parse(manifestBytes: ByteArray, spritesheetBytes: ByteArray, fallbackId: String = "pet"): CodexV2ParseOutcome {
        if (manifestBytes.size > PetInputLimits.MAX_MANIFEST_BYTES) return failure(limit(manifestBytes.size))
        if (spritesheetBytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES) return failure(CodexV2Error.InputLimitExceeded(
            "spritesheet is ${spritesheetBytes.size} bytes; maximum is ${PetInputLimits.MAX_SPRITESHEET_BYTES}",
        ))
        val info = EncodedSpritesheetProbe.probe(spritesheetBytes)
            ?: return failure(CodexV2Error.InvalidSpritesheetBytes("unrecognized, malformed, or animated spritesheet bytes"))
        if (info.format == SpritesheetFormat.PNG && !isStaticPng(spritesheetBytes)) {
            return failure(CodexV2Error.InvalidSpritesheetBytes("malformed or animated PNG container"))
        }
        return parseTrustedMetadata(manifestBytes, fallbackId, info)
    }

    /** Expert seam: does not prove metadata describes actual static image bytes. */
    public fun parseTrustedMetadata(manifestJson: String, fallbackId: String, spritesheet: SpritesheetInfo): CodexV2ParseOutcome {
        val manifest = try { read(manifestJson) } catch (e: Exception) { return failure(error(e)) }
        val errors = mutableListOf<CodexV2Error>()
        if (spritesheet.width != CodexV2.ATLAS_WIDTH || spritesheet.height != CodexV2.ATLAS_HEIGHT) {
            errors += CodexV2Error.GeometryMismatch(spritesheet.width, spritesheet.height)
        }
        if (spritesheet.format != SpritesheetFormat.PNG && spritesheet.format != SpritesheetFormat.WEBP) {
            errors += CodexV2Error.UnsupportedSpritesheetFormat(spritesheet.format)
        }
        if (errors.isNotEmpty()) return CodexV2ParseOutcome.Failure(CodexV2Report(errors))
        return try {
            val identity = normalizePetIdentity(manifest.id, manifest.displayName, manifest.description, fallbackId)
            CodexV2ParseOutcome.Success(PetDefinition(identity.id, identity.displayName, identity.description,
                CodexV2.geometry(), CodexV2.FRAME_COUNT, CodexV2.animations(), PetAnimations.Idle), manifest.path)
        } catch (e: IllegalArgumentException) {
            failure(CodexV2Error.NormalizationFailed("invariant violation during normalization: ${e.message}"))
        }
    }

    /** Strict UTF-8 byte variant of the bounded trusted metadata seam. */
    public fun parseTrustedMetadata(manifestBytes: ByteArray, fallbackId: String, spritesheet: SpritesheetInfo): CodexV2ParseOutcome = try {
        parseTrustedMetadata(decode(manifestBytes), fallbackId, spritesheet)
    } catch (e: Exception) { failure(error(e)) }

    // Walk actual chunk boundaries (never scan compressed IDAT payload). Long
    // lengths prevent overflow; require a complete IEND and some image data.
    private fun isStaticPng(bytes: ByteArray): Boolean {
        var at = 8
        var hasData = false
        while (at <= bytes.size - 12) {
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at + it].toLong() and 255) }
            val end = at.toLong() + 12 + length
            if (end > bytes.size) return false
            fun type(name: String): Boolean = (0..3).all { bytes[at + 4 + it].toInt() == name[it].code }
            if (type("acTL") || type("fcTL") || type("fdAT")) return false
            if (type("IDAT")) hasData = true
            if (type("IEND")) return length == 0L && hasData && end == bytes.size.toLong()
            at = end.toInt()
        }
        return false
    }
}
