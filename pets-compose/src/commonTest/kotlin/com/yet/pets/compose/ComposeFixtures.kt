package com.yet.pets.compose

import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetPackageParser
import com.yet.pets.core.PetParseOutcome
import com.yet.pets.core.SpritesheetFormat
import com.yet.pets.core.SpritesheetInfo
import kotlin.io.encoding.Base64
import kotlin.test.assertIs

/**
 * Shared fixtures built only from public APIs: core parsing plus tiny
 * encoded images decoded from Base64 literals, so no binary files are
 * vendored and no third-party assets are involved.
 */
internal fun testDefinition(json: String = "{}"): PetDefinition {
    val outcome = PetPackageParser.parseTrustedMetadata(
        json,
        "test",
        SpritesheetInfo(1536, 1872, SpritesheetFormat.PNG),
    )
    assertIs<PetParseOutcome.Success>(outcome, "expected parse success, got $outcome")
    return outcome.definition
}

/** 1x1 PNG, decoded via stdlib Base64 so no binary fixture is vendored. */
internal val tinyPngBytes: ByteArray
    get() = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

internal fun decodedTinyPng(): DecodedAtlas = decodeAtlasBytes(tinyPngBytes)
