package com.nhnengineering.rftest.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nhnengineering.rftest.model.Floorplan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Floorplan images, copied into app storage rather than referenced by URI.
 *
 * A picked `content://` URI is a temporary grant. It can be revoked, and it breaks outright if the
 * source is a cloud provider that goes offline or a file the user later moves. A session recorded
 * against a floorplan that cannot be reopened is a session whose positions mean nothing — so the
 * image is copied in at pick time and referenced afterwards by a stable filename.
 *
 * That filename is what goes in the CSV, which means an exported session and its floorplan can be
 * handed over together and still line up.
 */
object FloorplanStore {

    private const val TAG = "FloorplanStore"

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "floorplans").apply { mkdirs() }

    suspend fun list(context: Context): List<Floorplan> = withContext(Dispatchers.IO) {
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { describe(it) }
            ?: emptyList()
    }

    /**
     * Copies a picked image into app storage and returns its descriptor.
     *
     * Dimensions are read with `inJustDecodeBounds`, which parses only the header — a resort
     * floorplan can be a very large image, and decoding the pixels merely to learn its aspect ratio
     * would risk an OutOfMemory on a device already under Android 17's per-app RAM limits.
     */
    suspend fun import(context: Context, uri: Uri, suggestedName: String?): Floorplan? =
        withContext(Dispatchers.IO) {
            try {
                val safe = (suggestedName ?: "floorplan")
                    .substringAfterLast('/')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(64)
                val base = safe.substringBeforeLast('.', safe).ifBlank { "floorplan" }

                // Walk-test floor plans ship as vendor PDFs far more often than as raster images.
                // A PDF is rasterised to a PNG here, so everything downstream (list/describe/decode)
                // stays image-only and unchanged.
                val isPdf = context.contentResolver.getType(uri) == "application/pdf" ||
                    safe.substringAfterLast('.', "").equals("pdf", ignoreCase = true)
                if (isPdf) {
                    return@withContext importPdf(context, uri, base)
                }

                val ext = safe.substringAfterLast('.', "").lowercase()
                    .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"

                val target = uniqueFile(context, base, ext)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null

                describe(target)
            } catch (e: Exception) {
                Log.w(TAG, "floorplan import failed", e)
                null
            }
        }

    private fun uniqueFile(context: Context, base: String, ext: String): File {
        var target = File(dir(context), "$base.$ext")
        var n = 1
        while (target.exists()) {
            target = File(dir(context), "$base-$n.$ext")
            n++
        }
        return target
    }

    /** Resolution to rasterise a PDF page at. PDF units are points (1/72"); this is ~150 DPI, crisp
     *  for a line-drawing floor plan without producing an enormous bitmap. */
    private const val PDF_RENDER_SCALE = 150.0 / 72.0

    /** Cap on the longer rendered edge, so a large architectural sheet cannot OOM a device under
     *  Android 17's per-app RAM limits -- the transient ARGB_8888 bitmap is the constraint. */
    private const val PDF_MAX_EDGE_PX = 3000

    /**
     * Rasterises the first page of a PDF into a PNG in the floorplan store.
     *
     * [PdfRenderer] needs a seekable file descriptor, so the content is copied to a temp file first.
     * The page is rendered onto a white background because many PDFs are transparent and a floor
     * plan on a transparent (-> black, on ARGB_8888) field is unreadable.
     */
    private fun importPdf(context: Context, uri: Uri, base: String): Floorplan? {
        val tmp = File.createTempFile("fp_import_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount < 1) return null
                    renderer.openPage(0).use { page ->
                        var w = (page.width * PDF_RENDER_SCALE).toInt().coerceAtLeast(1)
                        var h = (page.height * PDF_RENDER_SCALE).toInt().coerceAtLeast(1)
                        val longest = max(w, h)
                        if (longest > PDF_MAX_EDGE_PX) {
                            val f = PDF_MAX_EDGE_PX.toDouble() / longest
                            w = (w * f).toInt().coerceAtLeast(1)
                            h = (h * f).toInt().coerceAtLeast(1)
                        }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val target = uniqueFile(context, base, "png")
                        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                        return describe(target)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PDF floorplan import failed", e)
            return null
        } finally {
            tmp.delete()
        }
    }

    fun file(context: Context, id: String): File = File(dir(context), id)

    private fun describe(f: File): Floorplan? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return Floorplan(
            id = f.name,
            displayName = f.nameWithoutExtension,
            widthPx = opts.outWidth,
            heightPx = opts.outHeight,
        )
    }
}
