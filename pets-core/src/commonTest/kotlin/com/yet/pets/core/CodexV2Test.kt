package com.yet.pets.core

import kotlin.math.*
import kotlin.test.*
import kotlin.io.encoding.Base64

class CodexV2Test {
    private val info = SpritesheetInfo(1536, 2288, SpritesheetFormat.WEBP)
    private val manifest = """{"spriteVersionNumber":2}"""
    private fun parse(json: String = manifest, sheet: SpritesheetInfo = info, fallback: String = "pet") =
        CodexV2PetPackageParser.parseTrustedMetadata(json, fallback, sheet)
    private fun error(json: String) = assertIs<CodexV2ParseOutcome.Failure>(parse(json)).report.errors.single()

    @Test fun literalVersionIsRequired() {
        assertIs<CodexV2ParseOutcome.Success>(parse())
        for (v in listOf("1", "3", "0", "-2", "null", "true", "\"2\"", "2.0", "2e0", "[]", "{}")) {
            assertIs<CodexV2Error.UnsupportedVersion>(error("""{"spriteVersionNumber":$v}"""), v)
        }
        assertIs<CodexV2Error.UnsupportedVersion>(error("{}"))
    }
    @Test fun malformedMetadataAndOverridePolicy() {
        for (j in listOf("{", "[]", "null", """{"spriteVersionNumber":2,"id":3}""")) {
            assertIs<CodexV2Error.MalformedManifest>(error(j))
        }
        for (member in listOf("frame", "animations")) for (value in listOf("null", "{}", "[]")) {
            assertEquals(member, assertIs<CodexV2Error.UnsupportedOverride>(error("""{"spriteVersionNumber":2,"$member":$value}""")).member)
        }
        assertIs<CodexV2ParseOutcome.Success>(parse("""{"spriteVersionNumber":2,"future":{"anything":true}}"""))
    }
    @Test fun metadataAndPathFallbackMatchIdentityPolicy() {
        val success = assertIs<CodexV2ParseOutcome.Success>(parse("""{"spriteVersionNumber":2,"id":" x ","displayName":" X ","description":" d ","spritesheetPath":" sheet.png "}"""))
        assertEquals("x", success.definition.id); assertEquals("X", success.definition.displayName)
        assertEquals("d", success.definition.description); assertEquals("sheet.png", success.spritesheetPath)
        val empty = assertIs<CodexV2ParseOutcome.Success>(parse("""{"spriteVersionNumber":2,"id":" ","displayName":null,"spritesheetPath":" "}""", fallback=" "))
        assertEquals("pet", empty.definition.id); assertEquals("pet", empty.definition.displayName)
        assertEquals("", empty.definition.description); assertEquals("spritesheet.webp", empty.spritesheetPath)
        assertEquals(empty.spritesheetPath, assertIs<CodexV2SpritesheetPathOutcome.Success>(CodexV2PetPackageParser.spritesheetPathOf(manifest.encodeToByteArray())).path)
        assertIs<CodexV2SpritesheetPathOutcome.Failure>(CodexV2PetPackageParser.spritesheetPathOf("{}"))
    }
    @Test fun fixedGeometryFormatsAndUnchangedStandardTracks() {
        val pet = assertIs<CodexV2ParseOutcome.Success>(parse()).definition
        assertEquals(88, pet.frameCount); assertEquals(PetAnimations.Idle, pet.defaultAnimationKey)
        assertEquals(AtlasGeometry(1536,2288,8,11,192,208), pet.geometry)
        for ((key, animation) in CodexV1.defaultAnimations()) assertEquals(animation, pet.animation(key), key.value)
        for ((w,h) in listOf(1536 to 1872,1535 to 2288,1536 to 2289)) {
            val e = assertIs<CodexV2ParseOutcome.Failure>(parse(sheet=info.copy(width=w,height=h))).report.errors.single()
            assertIs<CodexV2Error.GeometryMismatch>(e); assertTrue(e.message.contains("1536x2288"))
        }
        for (format in listOf(SpritesheetFormat.GIF,SpritesheetFormat.JPEG,SpritesheetFormat.UNKNOWN)) {
            assertIs<CodexV2Error.UnsupportedSpritesheetFormat>(assertIs<CodexV2ParseOutcome.Failure>(parse(sheet=info.copy(format=format))).report.errors.single())
        }
        assertIs<CodexV2ParseOutcome.Success>(parse(sheet=info.copy(format=SpritesheetFormat.PNG)))
    }
    @Test fun sixteenLookTracksAreHeldAndHaveExactIndices() {
        val names = listOf("000","022.5","045","067.5","090","112.5","135","157.5","180","202.5","225","247.5","270","292.5","315","337.5")
        val pet=assertIs<CodexV2ParseOutcome.Success>(parse()).definition
        for ((index,name) in names.withIndex()) {
            val key=PetAnimationKey("look-$name"); val animation=assertNotNull(pet.animation(key))
            assertEquals(1,animation.frames.size); assertEquals(72+index,animation.frames.single().spriteIndex)
            assertTrue(animation.frames.single().durationNanos>0); assertEquals(0,animation.loopStart)
            for (elapsed in listOf(0L,1L,Long.MAX_VALUE)) assertEquals(PetPlaybackSample(key,72+index,null),samplePetAnimation(pet,key,elapsed))
            val angle=index*PI/8
            assertEquals(key,CodexV2.lookAnimationKeyForOffset(10*sin(angle),-10*cos(angle)))
        }
    }
    @Test fun lookSelectionUsesClockwiseNearestSectorAndSafeNeutralRadius() {
        for ((x,y,key) in listOf(Triple(0.0,-10.0,"look-000"),Triple(10.0,0.0,"look-090"),Triple(0.0,10.0,"look-180"),Triple(-10.0,0.0,"look-270"),Triple(10.0,-10.0,"look-045"),Triple(10.0,10.0,"look-135"),Triple(-10.0,10.0,"look-225"),Triple(-10.0,-10.0,"look-315"))) assertEquals(key,CodexV2.lookAnimationKeyForOffset(x,y)?.value)
        for (angle in listOf(11.25,33.75,348.75)) {
            val rad=angle*PI/180
            val expected=if(angle==11.25) "look-022.5" else if(angle==33.75) "look-045" else "look-000"
            assertEquals(expected,CodexV2.lookAnimationKeyForOffset(10*sin(rad),-10*cos(rad))?.value)
            assertNotEquals(expected,CodexV2.lookAnimationKeyForOffset(10*sin(rad-1e-8),-10*cos(rad-1e-8))?.value)
        }
        for ((x,y) in listOf(0.0 to 0.0,1.0 to 0.0,0.6 to 0.8,Double.NaN to 1.0,Double.POSITIVE_INFINITY to 0.0,1.0 to Double.NEGATIVE_INFINITY)) assertNull(CodexV2.lookAnimationKeyForOffset(x,y))
        assertEquals("look-090",CodexV2.lookAnimationKeyForOffset(Double.MAX_VALUE,0.0)?.value)
        assertEquals("look-135",CodexV2.lookAnimationKeyForOffset(Double.MAX_VALUE,Double.MAX_VALUE)?.value)
    }
    @Test fun allManifestSeamsBoundUtf8BeforeParsing() {
        val exact=manifest+" ".repeat(PetInputLimits.MAX_MANIFEST_BYTES-manifest.length)
        assertIs<CodexV2ParseOutcome.Success>(parse(exact))
        for (j in listOf(exact+" ","é".repeat(40000))) {
            assertIs<CodexV2Error.InputLimitExceeded>(error(j))
            assertIs<CodexV2Error.InputLimitExceeded>(assertIs<CodexV2SpritesheetPathOutcome.Failure>(CodexV2PetPackageParser.spritesheetPathOf(j)).error)
        }
        val invalid=byteArrayOf(0xc3.toByte(),0x28)
        assertIs<CodexV2Error.MalformedManifest>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parseTrustedMetadata(invalid,"pet",info)).report.errors.single())
        assertIs<CodexV2Error.MalformedManifest>(assertIs<CodexV2SpritesheetPathOutcome.Failure>(CodexV2PetPackageParser.spritesheetPathOf(invalid)).error)
    }
    @Test fun rawImageLimitsMalformedAndAnimatedBytes() {
        for (bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3),Base64.decode("UklGRsQAAABXRUJQVlA4WAoAAAACAAAADwAACwAAQU5JTQYAAAAAAAAAAABBTk1GSgAAAAAAAAAAAA8AAAsAAMgAAAJWUDggMgAAADABAJ0BKhAADAABQCYloAADcAD+8ut///mwP/bz/wR6Af//0uD//pcH//S4P/SkAAAAQU5NRkYAAAAAAAAAAAAPAAALAADIAAAAVlA4IC4AAAA0AQCdASoQAAwAAAAmJaAAA3AA/vtV4///S4P/+lwf/9Lg/9Lg//rV5Vesq6AA"))) assertIs<CodexV2Error.InvalidSpritesheetBytes>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(manifest.encodeToByteArray(),bytes)).report.errors.single())
        assertIs<CodexV2Error.InputLimitExceeded>(assertIs<CodexV2ParseOutcome.Failure>(CodexV2PetPackageParser.parse(manifest.encodeToByteArray(),ByteArray(PetInputLimits.MAX_SPRITESHEET_BYTES+1))).report.errors.single())
    }
}
