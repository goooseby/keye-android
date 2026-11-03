package io.github.goooseby.keye

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDate

class MediaLibrary(private val context: Context) {
    private val root = File(context.filesDir, "materials").apply { mkdirs() }

    fun file(relativePath: String): File {
        val target = File(context.filesDir, relativePath).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "无效的课件路径" }
        return target
    }

    private fun folder(id: String) = File(root, id).apply { mkdirs() }
    private fun relative(file: File) = file.relativeTo(context.filesDir).invariantSeparatorsPath

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "导入课件"
        }
        return uri.lastPathSegment ?: "导入课件"
    }

    private fun copy(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun decode(file: File, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是可读取的图片" }
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
        })) { "图片解码失败" }
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(270f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
            else -> return decoded
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private fun renderPdfPage(renderer: PdfRenderer, index: Int, maxDimension: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = minOf(maxDimension.toFloat() / page.width, maxDimension.toFloat() / page.height)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bitmap
        }
    }

    private fun openPdf(descriptor: ParcelFileDescriptor): PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (error: SecurityException) {
        throw IllegalArgumentException("PDF 已加密，请先解密后导入", error)
    } catch (error: java.io.IOException) {
        throw IllegalArgumentException("PDF 无法读取，请确认文件完整且格式正确", error)
    }

    private fun writePreview(bitmap: Bitmap, target: File) {
        val scale = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(preview)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            FileOutputStream(target).use { check(preview.compress(Bitmap.CompressFormat.JPEG, 86, it)) }
        } finally {
            preview.recycle()
        }
    }

    fun importImages(uris: List<Uri>, courseId: String?, progress: (Int, Int) -> Unit): StudyMaterial {
        require(uris.isNotEmpty()) { "没有选择图片" }
        val id = newId()
        val dir = folder(id)
        try {
            val pages = uris.mapIndexed { index, uri ->
                val source = File(dir, "%05d-original".format(index + 1))
                val preview = File(dir, "%05d-preview.jpg".format(index + 1))
                copy(uri, source)
                val bitmap = decode(source, 2400)
                try { writePreview(bitmap, preview) } finally { bitmap.recycle() }
                progress(index + 1, uris.size)
                PageFile(relative(source), relative(preview))
            }
            return StudyMaterial(
                id = id, sourceKey = "local:$id", title = displayName(uris.first()).substringBeforeLast('.'),
                day = LocalDate.now().toString(), courseId = courseId, kind = "images", pages = pages
            )
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importPdf(uri: Uri, courseId: String?, progress: (Int, Int) -> Unit): StudyMaterial {
        val id = newId()
        val dir = folder(id)
        try {
            val original = File(dir, "original.pdf")
            copy(uri, original)
            val pages = mutableListOf<PageFile>()
            ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                openPdf(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "PDF 中没有页面" }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val scale = minOf(1600f / page.width, 1600f / page.height)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val preview = File(dir, "%05d-preview.jpg".format(index + 1))
                            writePreview(bitmap, preview)
                            bitmap.recycle()
                            pages += PageFile(relative(original), relative(preview), index)
                        }
                        progress(index + 1, renderer.pageCount)
                    }
                }
            }
            return StudyMaterial(
                id = id, sourceKey = "local:$id", title = displayName(uri).substringBeforeLast('.'),
                day = LocalDate.now().toString(), courseId = courseId, kind = "pdf", pages = pages
            )
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun downloadedImage(directoryId: String, index: Int, bytes: ByteArray): PageFile {
        val dir = folder(directoryId)
        val source = File(dir, "%05d-original".format(index + 1))
        val preview = File(dir, "%05d-preview.jpg".format(index + 1))
        source.writeBytes(bytes)
        val bitmap = decode(source, 2400)
        try { writePreview(bitmap, preview) } finally { bitmap.recycle() }
        return PageFile(relative(source), relative(preview))
    }

    fun classroomImage(materialId: String,key: String,bytes: ByteArray): PageFile {
        require(Regex("[a-f0-9]{64}").matches(key))
        val dir=folder(materialId)
        val prefix="follow-${key}-${newId().take(8)}"
        val source=File(dir,"$prefix-original")
        val preview=File(dir,"$prefix-preview.jpg")
        try {
            source.writeBytes(bytes)
            val bitmap=decode(source,2400)
            try {writePreview(bitmap,preview)} finally {bitmap.recycle()}
            return PageFile(relative(source),relative(preview),remoteKey=key)
        } catch(error: Exception) {source.delete();preview.delete();throw error}
    }
    fun discardClassroomPage(page: PageFile) {
        file(page.source).takeIf {it.name.startsWith("follow-")}?.delete()
        file(page.preview).takeIf {it.name.startsWith("follow-")}?.delete()
    }

    fun removeIncomplete(id: String) {
        File(root, id).deleteRecursively()
    }

    /** Unfiltered single PDFs are copied; other exports rasterize selected pages. */
    fun export(materials: List<StudyMaterial>, output: OutputStream, selectedPages: Map<String, Set<Int>>? = null, includeNotes: Boolean=true, progress: (Int, Int) -> Unit) {
        // A direct PDF export keeps text, links, and vector pages intact.
        val single = materials.singleOrNull()
        if (single?.kind == "pdf" && (!includeNotes || single.pageNotes.isEmpty()) && (selectedPages?.get(single.id) ?: ((1..single.pages.size).toSet()-single.excluded)) == (1..single.pages.size).toSet()) {
            val original = single.pages.firstOrNull()?.source ?: error("PDF 中没有页面")
            file(original).inputStream().use { it.copyTo(output) }
            progress(single.pages.size, single.pages.size)
            return
        }
        val selected = materials.flatMap { material ->
            val included=selectedPages?.get(material.id) ?: ((1..material.pages.size).toSet()-material.excluded)
            material.pages.mapIndexedNotNull { index, page ->
                if(index+1 !in included) null else page to if(includeNotes) material.pageNotes[index+1].orEmpty() else ""
            }
        }
        require(selected.isNotEmpty()) { "请选择至少一页" }
        val document = PdfDocument()
        val renderers = mutableMapOf<String, Pair<ParcelFileDescriptor, PdfRenderer>>()
        try {
            selected.forEachIndexed { index, (page,note) ->
                val original = if (page.pdfPage != null) {
                    val renderer = renderers.getOrPut(page.source) {
                        val descriptor = ParcelFileDescriptor.open(file(page.source), ParcelFileDescriptor.MODE_READ_ONLY)
                        try {
                            descriptor to openPdf(descriptor)
                        } catch (error: Exception) {
                            descriptor.close()
                            throw error
                        }
                    }.second
                    renderPdfPage(renderer, page.pdfPage, 2400)
                } else decode(file(page.source), 2400)
                val bitmap=withPageNote(original,note)
                try {
                    val width = 1240
                    val height = (width.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                    val info = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                    val pdfPage = document.startPage(info)
                    pdfPage.canvas.drawColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setScale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    }
                    pdfPage.canvas.drawBitmap(bitmap, matrix, null)
                    document.finishPage(pdfPage)
                } finally {
                    bitmap.recycle()
                }
                progress(index + 1, selected.size)
            }
            document.writeTo(output)
        } finally {
            renderers.values.forEach { (descriptor, renderer) ->
                renderer.close()
                descriptor.close()
            }
            document.close()
        }
    }
}
