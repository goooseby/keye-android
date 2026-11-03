package io.github.goooseby.keye
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

private data class LoadedPage(val bitmap: Bitmap?=null,val finished: Boolean=false)
private val pageCache=object: LruCache<String,Bitmap>(24*1024) {
    override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount/1024
}
@Composable
fun PageImage(file: File,maxDimension: Int,modifier: Modifier=Modifier) {
    val stamp=file.lastModified()
    val loaded by produceState(LoadedPage(),file.path,stamp,maxDimension) {
        val key="${file.path}:$stamp:$maxDimension"
        val cached=pageCache.get(key)
        if(cached!=null){value=LoadedPage(cached,true);return@produceState}
        val bitmap=withContext(Dispatchers.IO) {
            runCatching {
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,bounds)
                if(bounds.outWidth<=0||bounds.outHeight<=0) return@runCatching null
                var sample=1
                while(bounds.outWidth/sample>maxDimension||bounds.outHeight/sample>maxDimension) sample*=2
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
            }.getOrNull()
        }
        if(bitmap!=null) pageCache.put(key,bitmap)
        value=LoadedPage(bitmap,true)
    }
    if(loaded.bitmap!=null) Image(loaded.bitmap!!.asImageBitmap(),"课件页面",modifier,contentScale=ContentScale.Fit)
    else Box(modifier,contentAlignment=Alignment.Center) {
        if(!loaded.finished) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=1.5.dp)
        else CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){AppIcon("document", "页面无法读取",Modifier.size(20.dp))}
    }
}
