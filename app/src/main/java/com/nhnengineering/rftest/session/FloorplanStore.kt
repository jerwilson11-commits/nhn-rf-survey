package com.nhnengineering.rftest.session

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.nhnengineering.rftest.model.Floorplan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                val ext = safe.substringAfterLast('.', "").lowercase()
                    .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
                val base = safe.substringBeforeLast('.', safe).ifBlank { "floorplan" }

                var target = File(dir(context), "$base.$ext")
                var n = 1
                while (target.exists()) {
                    target = File(dir(context), "$base-$n.$ext")
                    n++
                }

                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null

                describe(target)
            } catch (e: Exception) {
                Log.w(TAG, "floorplan import failed", e)
                null
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
