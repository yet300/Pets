package com.pets.example

import com.yet.pets.core.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Format ownership only. All format validation belongs to the library parser. */
enum class ManifestFormat { PetsKmpV1, CodexV1, CodexV2, Unknown }

private fun readManifest(bytes: ByteArray): JsonElement? {
    if (bytes.size > PetInputLimits.MAX_MANIFEST_BYTES) return null
    return try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun classifyManifest(root: JsonElement): ManifestFormat {
    val obj = root as? JsonObject ?: return ManifestFormat.Unknown
    fun number(name: String, expected: String): Boolean {
        val value = obj[name] as? JsonPrimitive ?: return false
        return !value.isString && value.content == expected
    }
    if (number("spriteVersionNumber", "2")) return ManifestFormat.CodexV2
    val schema = obj["schema"] as? JsonPrimitive
    if (schema?.isString == true && schema.content == "pets-kmp" && number("schemaVersion", "1")) {
        return ManifestFormat.PetsKmpV1
    }
    // Explicit foreign schema/version never becomes an implicit Codex V1 document.
    if ("schema" in obj || "schemaVersion" in obj) return ManifestFormat.Unknown
    if ("spriteVersionNumber" in obj && !number("spriteVersionNumber", "1")) return ManifestFormat.Unknown
    // V1 has no required identity field: even {} uses the verified built-in defaults.
    // Recognize its member names without duplicating DTO or animation validation.
    val codexMembers = setOf("id", "displayName", "description", "spritesheetPath", "frame", "animations")
    if (obj.isEmpty() || number("spriteVersionNumber", "1") || obj.keys.any { it in codexMembers }) {
        return ManifestFormat.CodexV1
    }
    return ManifestFormat.Unknown
}

fun classifyPetManifest(manifestBytes: ByteArray): ManifestFormat =
    readManifest(manifestBytes)?.let(::classifyManifest) ?: ManifestFormat.Unknown

/** Keeps the selected parser's complete report for diagnostics without exposing it in UI. */
sealed interface ImportedPetResult {
    val userMessage: String? get() = null
    data class Success(val definition: PetDefinition) : ImportedPetResult
    data object UnsupportedFormat : ImportedPetResult {
        override val userMessage = "Unsupported pet format"
    }
    data class InvalidManifest(
        val petsKmpReport: PetsKmpReport? = null,
        val codexReport: PetCompatibilityReport? = null,
        val codexV2Report: CodexV2Report? = null,
    ) : ImportedPetResult {
        override val userMessage = "Invalid pet manifest"
    }
    data class InvalidSpritesheet(
        val petsKmpReport: PetsKmpReport? = null,
        val codexReport: PetCompatibilityReport? = null,
        val codexV2Report: CodexV2Report? = null,
    ) : ImportedPetResult {
        override val userMessage = "Invalid spritesheet"
    }
    data class ParseFailure(
        val petsKmpReport: PetsKmpReport? = null,
        val codexReport: PetCompatibilityReport? = null,
        val codexV2Report: CodexV2Report? = null,
    ) : ImportedPetResult {
        override val userMessage = "Invalid pet manifest"
    }
}

fun importedPetFallbackId(pickedFilename: String): String {
    val filename = pickedFilename.substringAfterLast('/').substringAfterLast('\\').trim()
    return filename.substringBeforeLast('.', filename).trim().ifEmpty { "imported-pet" }
}

/** The single import route used by preview and final Add on every platform. */
fun parseImportedPet(
    manifestBytes: ByteArray,
    spritesheetBytes: ByteArray,
    fallbackId: String = "imported-pet",
): ImportedPetResult {
    val root = readManifest(manifestBytes) ?: return ImportedPetResult.InvalidManifest()
    return when (classifyManifest(root)) {
        ManifestFormat.CodexV2 -> when (val result = CodexV2PetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)) {
            is CodexV2ParseOutcome.Success -> ImportedPetResult.Success(result.definition)
            is CodexV2ParseOutcome.Failure -> when {
                result.report.errors.any { it is CodexV2Error.InvalidSpritesheetBytes ||
                    it is CodexV2Error.UnsupportedSpritesheetFormat || it is CodexV2Error.GeometryMismatch } ||
                    spritesheetBytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES ->
                    ImportedPetResult.InvalidSpritesheet(codexV2Report = result.report)
                result.report.errors.any { it is CodexV2Error.MalformedManifest } ->
                    ImportedPetResult.InvalidManifest(codexV2Report = result.report)
                else -> ImportedPetResult.ParseFailure(codexV2Report = result.report)
            }
        }
        ManifestFormat.Unknown -> ImportedPetResult.UnsupportedFormat
        ManifestFormat.PetsKmpV1 -> when (val result = PetsKmpPackageParser.parse(manifestBytes, spritesheetBytes)) {
            is PetsKmpParseOutcome.Success -> ImportedPetResult.Success(result.definition)
            is PetsKmpParseOutcome.Failure -> when {
                result.report.errors.any { it is PetsKmpError.InvalidSpritesheetBytes ||
                    it is PetsKmpError.UnsupportedSpritesheetFormat || it is PetsKmpError.InvalidGrid } ||
                    spritesheetBytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES ->
                    ImportedPetResult.InvalidSpritesheet(petsKmpReport = result.report)
                result.report.errors.any { it is PetsKmpError.MalformedManifest } ->
                    ImportedPetResult.InvalidManifest(petsKmpReport = result.report)
                else -> ImportedPetResult.ParseFailure(petsKmpReport = result.report)
            }
        }
        ManifestFormat.CodexV1 -> when (val result = CodexPetPackageParser.parse(manifestBytes, spritesheetBytes, fallbackId)) {
            is PetParseOutcome.Success -> ImportedPetResult.Success(result.definition)
            is PetParseOutcome.Failure -> when {
                result.report.errors.any { it is PetCompatibilityError.InvalidSpritesheetBytes ||
                    it is PetCompatibilityError.UnsupportedSpritesheetFormat ||
                    it is PetCompatibilityError.UnsupportedAtlasDimensions } ||
                    spritesheetBytes.size > PetInputLimits.MAX_SPRITESHEET_BYTES ->
                    ImportedPetResult.InvalidSpritesheet(codexReport = result.report)
                result.report.errors.any { it is PetCompatibilityError.MalformedManifest } ->
                    ImportedPetResult.InvalidManifest(codexReport = result.report)
                else -> ImportedPetResult.ParseFailure(codexReport = result.report)
            }
        }
    }
}
