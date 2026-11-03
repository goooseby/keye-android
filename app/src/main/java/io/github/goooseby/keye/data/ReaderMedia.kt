package io.github.goooseby.keye.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File

/** Decode originals for reading/sharing, independently from small list thumbnails. */
internal fun renderReadingPage(file: File,pdfPage: Int?,maxSize: Int): Bitmap? {
    if(pdfPage!=null) return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->renderer.openPage(pdfPage).use { page ->
            val factor=maxSize.toFloat()/maxOf(page.width,page.height)
            Bitmap.createBitmap((page.width*factor).toInt().coerceAtLeast(1),(page.height*factor).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
                android.graphics.Canvas(it).drawColor(android.graphics.Color.WHITE)
                page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }}
    }
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
    require(bounds.outWidth>0 && bounds.outHeight>0) {"无法读取图片"}
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxSize*1.4) sample*=2
    val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return null
    val orientation=runCatching {ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix=Matrix().apply {
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 ->setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL ->setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE ->{setRotate(90f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_90 ->setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE ->{setRotate(-90f);postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_270 ->setRotate(-90f)
        }
    }
    if(matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also {if(it!==bitmap) bitmap.recycle()}
}
