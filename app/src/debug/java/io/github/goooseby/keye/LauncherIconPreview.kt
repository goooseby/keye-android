package io.github.goooseby.keye

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import kotlin.math.*

/** Debug-only comparison using Android's actual adaptive layer bounds at several sizes. */
internal class LauncherIconPreview(context: Context): View(context) {
    private val artwork=BitmapFactory.decodeResource(resources,R.drawable.ic_launcher_artwork)
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(30,38,46)}
    private val fractions=listOf(0f,1f/6f,.185f,.20f,.212f,2f/9f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(229,233,239))
        canvas.save()
        canvas.scale(density,density)
        val pageWidth=width/density
        paint.textSize=16f
        canvas.drawText("Adaptive icon / real Android layers",12f,24f,paint)
        paint.textSize=11f
        canvas.drawText("Circle                 Squircle               Rounded square",12f,44f,paint)
        fractions.forEachIndexed {row,fraction ->
            val top=62f+row*122f
            paint.textSize=12f
            canvas.drawText("Inset ${(fraction*1000).roundToInt()/10f}% / visible ${(150*(1-2*fraction)).roundToInt()}%",12f,top,paint)
            repeat(3) {shape ->
                val left=12f+shape*(pageWidth-24f)/3
                drawIcon(canvas,left,top+10f,64f,fraction,shape)
                drawIcon(canvas,left+72f,top+30f,24f,fraction,shape)
            }
        }
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas,x: Float,y: Float,size: Float,fraction: Float,shape: Int) {
        val foreground=InsetDrawable(BitmapDrawable(resources,artwork).apply {gravity=Gravity.FILL},fraction)
        val icon=AdaptiveIconDrawable(ColorDrawable(Color.rgb(243,249,253)),foreground)
        val pixels=(size*density).roundToInt()
        icon.setBounds(0,0,pixels,pixels)
        val mask=Path()
        when(shape) {
            0 ->mask.addCircle(pixels/2f,pixels/2f,pixels/2f,Path.Direction.CW)
            1 ->repeat(129) {index ->
                val angle=index*2*PI/128
                val dx=cos(angle);val dy=sin(angle)
                val px=(pixels/2*(1+sign(dx)*sqrt(abs(dx)))).toFloat()
                val py=(pixels/2*(1+sign(dy)*sqrt(abs(dy)))).toFloat()
                if(index==0) mask.moveTo(px,py) else mask.lineTo(px,py)
            }
            else ->mask.addRoundRect(0f,0f,pixels.toFloat(),pixels.toFloat(),pixels*.24f,pixels*.24f,Path.Direction.CW)
        }
        mask.close()
        canvas.save()
        canvas.translate(x,y)
        canvas.scale(1/density,1/density)
        canvas.clipPath(mask)
        icon.background.draw(canvas)
        icon.foreground.draw(canvas)
        canvas.restore()
    }
}
