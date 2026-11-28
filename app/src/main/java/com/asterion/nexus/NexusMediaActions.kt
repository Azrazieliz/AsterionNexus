package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.*
import android.view.View
import java.util.concurrent.Executors

/** Contextual operation sheet. All output is an independent registered item. */
object NexusMediaActions {
    private val worker=Executors.newSingleThreadExecutor()
    private fun item(activity:Activity,uri:Uri):NexusRegistry.Item =
        NexusRegistry(activity).find(uri.lastPathSegment)
            ?:throw IllegalStateException("Verified original unavailable")
    private fun active(a:Activity)=!a.isFinishing&&!a.isDestroyed
    private fun message(a:Activity,title:String,body:String){
        if(active(a))AlertDialog.Builder(a).setTitle(title).setMessage(body)
            .setPositiveButton("Close",null).show()
    }
    fun inspect(activity:Activity,uri:Uri){
        val progress=AlertDialog.Builder(activity).setTitle("Inspect media")
            .setMessage("Checking source and representative media data").setCancelable(false).show()
        worker.execute{
            val result=runCatching{NexusMediaOperations.inspect(activity.applicationContext,item(activity,uri))}
            activity.runOnUiThread{
                if(!active(activity))return@runOnUiThread
                progress.dismiss()
                result.onSuccess{report->
                    val summary=StringBuilder("Source SHA-256 verified\n")
                    val image=report.optJSONObject("image")
                    if(image!=null){
                        summary.append(image.optInt("width")).append(" × ").append(image.optInt("height"))
                            .append("\n").append(if(image.optBoolean("animated"))"Animated" else "Still image")
                            .append("\n").append(image.optString("color_space","Color space unspecified"))
                            .append("\nRepresentative decode passed")
                    }
                    val tracks=report.optJSONArray("tracks")
                    if(tracks!=null)for(i in 0 until tracks.length()){
                        val track=tracks.getJSONObject(i)
                        summary.append("\n\nTrack ").append(i+1).append(" · ").append(track.optString("mime"))
                        for(key in listOf("width","height","frame_rate","sample-rate","channel-count","language","duration_us","profile","level","bitrate","color-standard","color-transfer","color-range")){
                            if(track.has(key))summary.append("\n").append(key.replace('_',' ').replace('-',' ')).append(": ").append(track.optString(key))
                        }
                        val probes=track.optJSONArray("packet_probes")
                        if(probes!=null)summary.append("\nReadable packet probes: ").append((0 until probes.length()).count{probes.getJSONObject(it).optBoolean("packet_read")}).append("/").append(probes.length())
                    }
                    val frames=report.optJSONArray("representative_video_frames")
                    if(frames!=null)summary.append("\n\nDecoded frame probes: ").append((0 until frames.length()).count{frames.getJSONObject(it).optBoolean("decoded")}).append("/").append(frames.length())
                    summary.append("\n\n").append(report.optString("coverage"))
                    AlertDialog.Builder(activity).setTitle("Media inspection").setMessage(summary.toString())
                        .setPositiveButton("Close",null).setNeutralButton("Technical report"){_,_->
                            message(activity,"Technical report",report.toString(2))
                        }.show()
                }.onFailure{message(activity,"Inspection incomplete",it.message.orEmpty().take(240))}
            }
        }
    }
    fun derivative(activity:Activity,uri:Uri,type:String){
        val poster=type.startsWith("video/")
        val body=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            val pad=(20*resources.displayMetrics.density).toInt()
            setPadding(pad,pad,pad,pad)
        }
        fun label(text:String)=TextView(activity).apply{this.text=text}
        fun choices(values:List<String>)=Spinner(activity).apply{
            adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,values)
        }
        val sizes=listOf("1024","2048","4096")
        val size=choices(sizes).apply{setSelection(1)}
        val formats=listOf("PNG","WebP (lossless)","JPEG")
        val format=choices(formats)
        val time=EditText(activity).apply{hint="Frame position (seconds)";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("0")}
        val quality=SeekBar(activity).apply{max=99;progress=94}
        val qualityLabel=label("JPEG quality: 95")
        quality.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){}
            override fun onStopTrackingTouch(bar:SeekBar){}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){qualityLabel.text="JPEG quality: "+(value+1)}
        })
        format.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                quality.visibility=if(position==2)View.VISIBLE else View.GONE
                qualityLabel.visibility=quality.visibility
            }
        }
        body.addView(label("Maximum dimension (no upscaling)"));body.addView(size)
        body.addView(label("Output format"));body.addView(format)
        body.addView(qualityLabel);body.addView(quality)
        if(poster)body.addView(time)
        body.addView(label(if(poster)"Extracts a nearby keyframe. The video is retained."
            else "Still images only. Converted to sRGB; source EXIF and original bytes remain in the original. JPEG is unavailable for transparent images."))
        val dialog=AlertDialog.Builder(activity).setTitle(if(poster)"Extract video frame" else "Resize or convert image")
            .setView(body).setNegativeButton("Cancel",null).setPositiveButton("Create",null).create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val seconds=if(poster)time.text.toString().toLongOrNull() else 0L
                if(seconds==null||seconds<0||seconds>Long.MAX_VALUE/1000000){
                    time.error="Enter a valid time";return@setOnClickListener
                }
                val extension=listOf("png","webp","jpg")[format.selectedItemPosition]
                val max=sizes[size.selectedItemPosition].toInt()
                val q=if(extension=="jpg")quality.progress+1 else 100
                dialog.dismiss()
                val progress=AlertDialog.Builder(activity).setTitle("Creating verified output")
                    .setMessage("Original will remain unchanged").setCancelable(false).show()
                worker.execute{
                    val result=runCatching{NexusMediaOperations.derive(activity.applicationContext,item(activity,uri),
                        if(poster)"poster" else "resize",max,extension,q,seconds*1000000)}
                    activity.runOnUiThread{
                        if(!active(activity))return@runOnUiThread
                        progress.dismiss()
                        result.onSuccess{output->
                            AlertDialog.Builder(activity).setTitle("Verified output")
                                .setMessage(output.displayName+"\n"+android.text.format.Formatter.formatFileSize(activity,output.length)+"\nOriginal retained.")
                                .setPositiveButton("Open"){_,_->NexusFileOpen.open(activity,NexusLibraryProvider.uriFor(activity,output.sha256),output.mime,output.displayName)}
                                .setNegativeButton("Close",null).show()
                        }.onFailure{message(activity,"Output not completed",it.message.orEmpty().take(240))}
                    }
                }
            }
        }
        dialog.show()
    }
}
