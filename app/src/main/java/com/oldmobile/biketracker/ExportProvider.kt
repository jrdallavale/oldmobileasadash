package com.oldmobile.biketracker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Minimal read-only provider so the share sheet can hand exported files to other apps. */
class ExportProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        val dir = exportDir(context!!)
        val f = File(dir, uri.lastPathSegment ?: throw FileNotFoundException())
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.toString().endsWith(".gpx")) "application/gpx+xml" else "application/vnd.ant.fit"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        /** Android/data/com.oldmobile.biketracker/files/exports: also reachable over USB. */
        fun exportDir(ctx: android.content.Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports").apply { mkdirs() }

        fun uri(ctx: android.content.Context, f: File): Uri = Uri.parse("content://${ctx.packageName}.exports/${f.name}")
    }
}
