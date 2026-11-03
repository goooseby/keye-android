package io.github.goooseby.keye.data.media


import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Notes are a separate footer; the original course page is never painted over. */
internal fun withPageNote(bitmap: Bitmap,note: String): Bitmap {
    if(note.isBlank()) return bitmap
    try {
        val margin=(bitmap.width*.035f).toInt().coerceAtLeast(16)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(37,48,58);textSize=(bitmap.width/58f).coerceIn(18f,36f)}
        val text="批注\n${note.take(2000)}"
        val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,bitmap.width-margin*2).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(6f,1.15f).build()
        val output=Bitmap.createBitmap(bitmap.width,bitmap.height+layout.height+margin*2,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output)
        canvas.drawColor(Color.WHITE);canvas.drawBitmap(bitmap,0f,0f,null)
        canvas.drawLine(margin.toFloat(),bitmap.height.toFloat(),(bitmap.width-margin).toFloat(),bitmap.height.toFloat(),Paint().apply {color=Color.rgb(210,220,232);strokeWidth=2f})
        canvas.save();canvas.translate(margin.toFloat(),(bitmap.height+margin).toFloat());layout.draw(canvas);canvas.restore()
        return output
    } finally {bitmap.recycle()}
}
