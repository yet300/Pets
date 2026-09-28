package com.yet.pets.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device execution verification for the committed Android decoder
 * ([decodePlatformImageBytes] = `BitmapFactory.decodeByteArray` +
 * `asImageBitmap`, wrapped by [decodeAtlasBytes]).
 *
 * This suite executes the REAL production decoder on a real emulator/device;
 * compile-only evidence is explicitly not accepted for the Phase 3 gate.
 * Fixtures are host-generated independent-encoder vectors (see
 * [AndroidDecoderFixtures]); dimension and first-frame pixel assertions prove
 * the platform codec decoded them rather than merely sniffing magic.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDecoderVerificationTest {

    private data class Rgb(val r: Int, val g: Int, val b: Int)

    private fun pixelOf(bitmap: Bitmap, x: Int, y: Int): Rgb {
        val p = bitmap.getPixel(x, y)
        return Rgb(Color.red(p), Color.green(p), Color.blue(p))
    }

    private fun assertPixelNear(actual: Rgb, expected: Rgb, tolerance: Int, label: String) {
        assertTrue(
            kotlin.math.abs(actual.r - expected.r) <= tolerance &&
                kotlin.math.abs(actual.g - expected.g) <= tolerance &&
                kotlin.math.abs(actual.b - expected.b) <= tolerance,
            "$label (API ${Build.VERSION.SDK_INT}): expected ~$expected, was $actual (tol $tolerance)",
        )
    }

    /** Runs the production decoder and asserts [PetAtlasState.Ready] + dimensions. */
    private fun assertReady(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int, label: String): Bitmap {
        val decoded = decodeAtlasBytes(bytes)
        assertEquals(
            PetAtlasState.Ready,
            decoded.state,
            "$label (API ${Build.VERSION.SDK_INT}): expected Ready, got ${decoded.state}",
        )
        val bitmap = assertNotNull(decoded.bitmap, "$label: Ready must carry a bitmap").asAndroidBitmap()
        assertEquals(expectedWidth, bitmap.width, "$label: width rescaled on API ${Build.VERSION.SDK_INT}")
        assertEquals(expectedHeight, bitmap.height, "$label: height rescaled on API ${Build.VERSION.SDK_INT}")
        println("ANDROID-DECODE $label api=${Build.VERSION.SDK_INT} state=Ready ${bitmap.width}x${bitmap.height}")
        return bitmap
    }

    private fun assertFailed(bytes: ByteArray, label: String) {
        val decoded = decodeAtlasBytes(bytes)
        assertTrue(
            decoded.state is PetAtlasState.Failed,
            "$label (API ${Build.VERSION.SDK_INT}): expected Failed, got ${decoded.state}",
        )
        assertTrue(
            (decoded.state as PetAtlasState.Failed).reason.isNotBlank(),
            "$label: failure reason must be non-blank",
        )
        assertNull(decoded.bitmap, "$label: Failed must not carry a bitmap")
        println("ANDROID-DECODE $label api=${Build.VERSION.SDK_INT} state=Failed")
    }

    // PNG ---------------------------------------------------------------

    @Test
    fun tinyValidPngDecodes() {
        val bitmap = assertReady(decodeFixture(FIX_PNG_VALID_B64), 12, 10, "png-valid")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(200, 40, 60), 0, "png-valid pixel")
    }

    @Test
    fun byteArrayDecodeIsNotDensityRescaled() {
        // Raw-ByteArray decode must not apply resource-density scaling:
        // encoded 192x208 -> decoded 192x208 exactly.
        val bitmap = assertReady(decodeFixture(FIX_PNG_DENSITY_B64), 192, 208, "png-192x208")
        // Observed: BitmapFactory tags raw-decode bitmaps with the device
        // density (e.g. 420) rather than DENSITY_NONE. Dimensions are still
        // exact (asserted above), so no resource-density rescaling occurs;
        // the value is logged for the verification report.
        println("ANDROID-DECODE png-192x208 bitmapDensity=${bitmap.density}")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(10, 200, 120), 0, "png-192x208 pixel")
    }

    @Test
    fun fullAtlas1536x1872DecodesAtExactSize() {
        // Legal full-size atlas generated on-device (PNG is losslessly encoded,
        // so quadrant pixels are exact): exercises the ~11.5 MiB decoded path.
        val w = 1536
        val h = 1872
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, w / 2f, h / 2f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(w / 2f, 0f, w.toFloat(), h / 2f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, h / 2f, w / 2f, h.toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRect(w / 2f, h / 2f, w.toFloat(), h.toFloat(), paint)
        val out = ByteArrayOutputStream()
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG compression must succeed")
        source.recycle()
        val bytes = out.toByteArray()
        println("ANDROID-DECODE atlas-png api=${Build.VERSION.SDK_INT} encodedBytes=${bytes.size}")

        val bitmap = assertReady(bytes, w, h, "png-atlas-1536x1872")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(255, 0, 0), 0, "atlas top-left")
        assertPixelNear(pixelOf(bitmap, w - 1, 0), Rgb(0, 255, 0), 0, "atlas top-right")
        assertPixelNear(pixelOf(bitmap, 0, h - 1), Rgb(0, 0, 255), 0, "atlas bottom-left")
        assertPixelNear(pixelOf(bitmap, w - 1, h - 1), Rgb(255, 255, 255), 0, "atlas bottom-right")
    }

    @Test
    fun malformedPngFails() {
        assertFailed(decodeFixture(FIX_PNG_VALID_B64).copyOf(decodeFixture(FIX_PNG_VALID_B64).size / 2), "png-truncated")
    }

    // JPEG --------------------------------------------------------------

    @Test
    fun validJpegDecodes() {
        val bitmap = assertReady(decodeFixture(FIX_JPEG_VALID_B64), 16, 12, "jpeg-valid")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(200, 40, 60), 30, "jpeg-valid pixel")
    }

    @Test
    fun truncatedJpegFailsWithoutException() {
        val bytes = decodeFixture(FIX_JPEG_VALID_B64)
        assertFailed(bytes.copyOf(bytes.size / 3), "jpeg-truncated")
    }

    // GIF ---------------------------------------------------------------

    @Test
    fun staticGifDecodes() {
        val bitmap = assertReady(decodeFixture(FIX_GIF_STATIC_B64), 14, 11, "gif-static")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(255, 0, 0), 5, "gif-static pixel")
    }

    @Test
    fun animatedGifDecodesDeterministicFirstFrame() {
        // Contract needs one static atlas: the first frame is sufficient, and
        // it must be deterministic (red), not the second frame (blue).
        val bitmap = assertReady(decodeFixture(FIX_GIF_ANIMATED_B64), 14, 11, "gif-animated")
        val pixel = pixelOf(bitmap, 0, 0)
        println("ANDROID-DECODE gif-animated api=${Build.VERSION.SDK_INT} firstPixel=$pixel")
        assertPixelNear(pixel, Rgb(255, 0, 0), 30, "gif-animated first frame")
        assertTrue(pixel.b < 100, "gif-animated must not show second (blue) frame, was $pixel")
    }

    // WebP matrix -------------------------------------------------------

    @Test
    fun webpLossyVp8Decodes() {
        val bitmap = assertReady(decodeFixture(FIX_WEBP_LOSSY_B64), 16, 12, "webp-vp8-lossy")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(200, 40, 60), 40, "webp-vp8-lossy pixel")
    }

    @Test
    fun webpLosslessVp8lDecodes() {
        val bitmap = assertReady(decodeFixture(FIX_WEBP_LOSSLESS_B64), 16, 12, "webp-vp8l-lossless")
        assertPixelNear(pixelOf(bitmap, 0, 0), Rgb(200, 40, 60), 5, "webp-vp8l-lossless pixel")
    }

    @Test
    fun webpAlphaVp8xDecodes() {
        val bitmap = assertReady(decodeFixture(FIX_WEBP_ALPHA_B64), 16, 12, "webp-vp8x-alpha")
        // (1,0) is an opaque checker cell in the embedded fixture.
        val opaque = bitmap.getPixel(1, 0)
        assertEquals(255, Color.alpha(opaque), "webp-vp8x-alpha opaque cell must stay opaque")
        assertPixelNear(pixelOf(bitmap, 1, 0), Rgb(0, 180, 0), 40, "webp-vp8x-alpha pixel")
    }

    @Test
    fun animatedWebpIsRejectedOnEveryApi() {
        val bytes = decodeFixture(FIX_WEBP_ANIMATED_B64)
        val decoded = decodeAtlasBytes(bytes)
        kotlin.test.assertIs<PetAtlasState.Failed>(decoded.state)
        assertNull(decoded.bitmap)
    }

    // Failure totality --------------------------------------------------

    @Test
    fun malformedCorpusAlwaysFailsWithoutCrash() {
        val png = decodeFixture(FIX_PNG_VALID_B64)
        val jpeg = decodeFixture(FIX_JPEG_VALID_B64)
        val gif = decodeFixture(FIX_GIF_STATIC_B64)
        val webp = decodeFixture(FIX_WEBP_LOSSLESS_B64)

        assertFailed(ByteArray(0), "corpus-empty")
        assertFailed(ByteArray(256) { (it * 31 + 7).toByte() }, "corpus-random")
        assertFailed(png.copyOf(png.size / 2), "corpus-truncated-png")
        assertFailed(jpeg.copyOf(jpeg.size / 3), "corpus-truncated-jpeg")
        assertFailed(gif.copyOf(gif.size / 2), "corpus-truncated-gif")
        assertFailed(webp.copyOf(webp.size / 2), "corpus-truncated-webp")

        // Correct magic + corrupt structure per container.
        val pngMagicCorrupt = ByteArray(64)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(pngMagicCorrupt)
        assertFailed(pngMagicCorrupt, "corpus-png-magic-corrupt")

        val jpegMagicCorrupt = ByteArray(64)
        jpegMagicCorrupt[0] = 0xFF.toByte()
        jpegMagicCorrupt[1] = 0xD8.toByte()
        jpegMagicCorrupt[2] = 0xFF.toByte()
        assertFailed(jpegMagicCorrupt, "corpus-jpeg-magic-corrupt")

        val gifMagicCorrupt = ByteArray(64)
        "GIF89a".encodeToByteArray().copyInto(gifMagicCorrupt)
        assertFailed(gifMagicCorrupt, "corpus-gif-magic-corrupt")

        val webpMagicCorrupt = ByteArray(64)
        "RIFF".encodeToByteArray().copyInto(webpMagicCorrupt)
        "WEBPVP8 ".encodeToByteArray().copyInto(webpMagicCorrupt, 8)
        assertFailed(webpMagicCorrupt, "corpus-webp-magic-corrupt")
    }
}
