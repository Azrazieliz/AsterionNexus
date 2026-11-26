package com.asterion.nexus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Integrated original image viewer. Safe bounded decode, pinch/double-tap zoom,
 * native Back and responsive control targets. Original files are never altered.
 */
class NexusImageViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xff0e1013.toInt()
        window.navigationBarColor=0xff0e1013.toInt()
        val source=intent.data
        if(source==null){finish();return}
        val title=intent.getStringExtra("title") ?: "Image"
        setContent{
            var reload by remember { mutableIntStateOf(0) }
            var failed by remember(source,reload){ mutableStateOf(false) }
            val bitmap by produceState<Bitmap?>(null,source,reload){
                value=withContext(Dispatchers.IO){
                    runCatching { boundedImage(source) }.getOrNull()
                }
                failed=value==null
            }
            var scale by remember(source,reload){ mutableFloatStateOf(1f) }
            var pan by remember(source,reload){ mutableStateOf(Offset.Zero) }
            Column(Modifier.fillMaxSize().background(NexusVisual.canvas)
                .systemBarsPadding()){
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp)
                    .padding(horizontal=8.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={finish()},
                        modifier=Modifier.heightIn(min=48.dp).widthIn(min=56.dp)){
                        Text("←",fontSize=24.sp,color=NexusVisual.silver)
                    }
                    Text(title,color=NexusVisual.text,fontSize=15.sp,
                        maxLines=1,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f))
                    Text((scale*100f).toInt().toString()+"%",
                        color=NexusVisual.muted,fontSize=12.sp,
                        modifier=Modifier.padding(horizontal=10.dp))
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)
                    .clipToBounds().background(NexusVisual.imageBackdrop),
                    contentAlignment=Alignment.Center){
                    val boundsX=constraints.maxWidth.toFloat()
                    val boundsY=constraints.maxHeight.toFloat()
                    val transform=rememberTransformableState{zoomChange,panChange,_->
                        val next=(scale*zoomChange).coerceIn(1f,6f)
                        val limitX=((next-1f)*boundsX/2f).coerceAtLeast(0f)
                        val limitY=((next-1f)*boundsY/2f).coerceAtLeast(0f)
                        scale=next
                        pan=if(next<=1.01f)Offset.Zero else Offset(
                            (pan.x+panChange.x).coerceIn(-limitX,limitX),
                            (pan.y+panChange.y).coerceIn(-limitY,limitY))
                    }
                    when {
                        bitmap!=null->Image(
                            bitmap=bitmap!!.asImageBitmap(),
                            contentDescription=title,
                            contentScale=ContentScale.Fit,
                            modifier=Modifier.fillMaxSize()
                                .graphicsLayer(
                                    scaleX=scale,scaleY=scale,
                                    translationX=pan.x,translationY=pan.y)
                                .transformable(transform)
                                .pointerInput(source){
                                    detectTapGestures(onDoubleTap={
                                        scale=if(scale>1.1f)1f else 2f
                                        pan=Offset.Zero
                                    })
                                })
                        failed->Column(
                            horizontalAlignment=Alignment.CenterHorizontally){
                            Text("Image could not be decoded",
                                color=NexusVisual.text,fontSize=14.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick={reload++},
                                shape=RoundedCornerShape(12.dp)){
                                Text("Retry")
                            }
                        }
                        else->CircularProgressIndicator(color=NexusVisual.silver)
                    }
                }
            }
        }
    }

    private fun boundedImage(uri:Uri):Bitmap?{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        contentResolver.openFileDescriptor(uri,"r")?.use{
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,bounds)
        } ?: return null
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null
        var sample=1
        while(bounds.outWidth/sample>3072||bounds.outHeight/sample>3072||
            (bounds.outWidth/sample).toLong()*(bounds.outHeight/sample)>10_000_000L){
            sample*=2
        }
        val decode=BitmapFactory.Options().apply{inSampleSize=sample}
        return contentResolver.openFileDescriptor(uri,"r")?.use{
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,decode)
        }
    }
}
