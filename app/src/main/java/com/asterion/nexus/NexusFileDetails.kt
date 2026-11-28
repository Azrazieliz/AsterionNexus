package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.ailm.android.runtime.StandaloneRuntime
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Opened-file metadata only. Core's existing manual preference remains
 * authoritative for mapped artwork. Other verified contents use a SHA identity
 * independent of display name or storage location, never a path-based rating.
 */
object NexusFileDetails {
    private val worker=Executors.newSingleThreadExecutor()
    private data class Target(val sha:String?,val coreId:Int?)
    private data class Scores(val manual:Int,val pending:Int?)
    private fun identify(activity:Activity,uri:Uri,mime:String):Target {
        val registry=NexusRegistry(activity)
        val sha=if(uri.authority==activity.packageName+".library"&&
            uri.pathSegments.size==2&&uri.pathSegments[0]=="original"){
            val item=registry.find(uri.lastPathSegment)
                ?:throw IllegalStateException("Verified original unavailable")
            item.sha256
        }else if(uri.authority in setOf("com.android.externalstorage.documents",
                "com.android.providers.media.documents","media")){
            // Local authorized content only: no whole-cloud-file downloads for ratings.
            val digest=MessageDigest.getInstance("SHA-256")
            activity.contentResolver.openInputStream(uri)?.use{input->
                val buf=ByteArray(131072)
                var total=0L
                while(true){
                    val n=input.read(buf)
                    if(n<0)break
                    if(n==0)continue
                    total+=n
                    check(total<=256L*1024L*1024L){"Use a verified offline copy for this large file"}
                    digest.update(buf,0,n)
                }
                check(total>0){"Empty original"}
            }?:throw IllegalStateException("File cannot be read")
            digest.digest().joinToString(""){ "%02x".format(it.toInt() and 255) }
        }else null
        val core=if(mime.startsWith("image/")){
            StandaloneRuntime.initialize(activity.applicationContext)
            StandaloneRuntime.nexusArtworkId(uri.toString(),sha)
        }else null
        return Target(sha,core)
    }
    private fun read(activity:Activity,target:Target):Scores{
        val local=target.sha?.let{NexusRegistry(activity).fileRating(it)}?:-1
        val core=target.coreId?:return Scores(local,null)
        val rating=(StandaloneRuntime.artworkRating(core)["manual_score"] as? Number)?.toInt()?:-1
        return Scores(rating,local.takeIf{it>=0})
    }
    private fun write(activity:Activity,target:Target,score:Int){
        check(score in -1..100)
        val registry=NexusRegistry(activity)
        if(target.coreId!=null){
            check(StandaloneRuntime.setArtworkRating(target.coreId,score)){"Core rating update failed"}
            // Resolve previously stored generic artwork rating only after the
            // owner's explicit save and successful native Core write.
            target.sha?.let{registry.setFileRating(it,-1)}
        }else{
            registry.setFileRating(target.sha?:throw IllegalStateException("No verified content identity"),score)
        }
    }
    @JvmStatic fun show(activity:Activity,uri:Uri,mime:String?,title:String?){
        showInternal(activity,uri,mime,title,null)
    }
    @JvmStatic fun showWithOpen(activity:Activity,uri:Uri,mime:String?,title:String?,open:Runnable){
        showInternal(activity,uri,mime,title,open)
    }
    private fun showInternal(activity:Activity,uri:Uri,mime:String?,title:String?,open:Runnable?){
        val type=mime?:activity.contentResolver.getType(uri)?:"application/octet-stream"
        val body=LinearLayout(activity).apply{
            orientation=LinearLayout.VERTICAL
            val pad=(20*resources.displayMetrics.density).toInt()
            setPadding(pad,pad,pad,pad)
        }
        val info=TextView(activity).apply{text=type}
        val status=TextView(activity).apply{text="Reading file metadata…"}
        val slider=SeekBar(activity).apply{max=100;progress=50;isEnabled=false}
        body.addView(info);body.addView(status);body.addView(slider)
        if(NexusArchiveOperation.available(activity,uri)||type.startsWith("image/")){
            body.addView(android.widget.Button(activity).apply{
                text="Add to collection"
                setOnClickListener{NexusCollectionActions.add(activity,uri,type)}
            })
        }
        if(uri.authority==com.openlocal.office.core.FgTeraboxDocumentsProvider.AUTHORITY){
            body.addView(android.widget.Button(activity).apply{
                text="Pin offline"
                setOnClickListener{NexusCloudActions.pin(activity,uri,title?: "File",type)}
            })
            body.addView(android.widget.Button(activity).apply{
                text="Move or rename"
                setOnClickListener{NexusCloudActions.move(activity,uri)}
            })
        }
        if(NexusArchiveOperation.available(activity,uri)){
            body.addView(android.widget.Button(activity).apply{
                text="Local storage and cloud copy"
                setOnClickListener{NexusRetentionActions.show(activity,uri)}
            })
            body.addView(android.widget.Button(activity).apply{
                text="Send to specialist"
                setOnClickListener{NexusPartnerActions.show(activity,uri)}
            })
            body.addView(android.widget.Button(activity).apply{
                text="Related editions and outputs"
                setOnClickListener{
                    worker.execute{
                        val result=runCatching{NexusRegistry(activity).related(uri.lastPathSegment,80)}
                        activity.runOnUiThread{
                            if(!activity.isFinishing&&!activity.isDestroyed){
                                result.onSuccess{rows->
                                    val dialog=AlertDialog.Builder(activity).setTitle("Related files").setPositiveButton("Close",null)
                                    if(rows.isEmpty())dialog.setMessage("No recorded related files")
                                    else dialog.setItems(rows.map{it.displayName}.toTypedArray()){_,which->
                                        val item=rows[which]
                                        NexusFileOpen.open(activity,NexusLibraryProvider.uriFor(activity,item.sha256),item.mime,item.displayName)
                                    }
                                    dialog.show()
                                }.onFailure{status.text="Related files unavailable"}
                            }
                        }
                    }
                }
            })
            body.addView(android.widget.Button(activity).apply{
                text="Verify or recover original"
                setOnClickListener{
                    activity.startActivity(android.content.Intent(activity,NexusRecoveryActivity::class.java)
                        .putExtra("sha256",uri.lastPathSegment))
                }
            })
        }
        if(NexusArchiveOperation.available(activity,uri)&&NexusBookIndex.supported(type)){
            body.addView(android.widget.Button(activity).apply{
                text="Offline translation"
                setOnClickListener{
                    worker.execute{
                        val sha=uri.lastPathSegment.orEmpty()
                        val queue=NexusTranslationQueue(activity)
                        val state=runCatching{queue.status(sha)}.getOrDefault("Translation status unavailable")
                        activity.runOnUiThread{
                            if(!activity.isFinishing&&!activity.isDestroyed)AlertDialog.Builder(activity)
                                .setTitle("Private offline translation").setMessage(state)
                                .setPositiveButton("Close",null)
                                .setNeutralButton("Retry / resume"){_,_->worker.execute{queue.retry(sha)}}
                                .setNegativeButton("Pause"){_,_->worker.execute{queue.pause(sha)}}.show()
                        }
                    }
                }
            })
            val indexButton=android.widget.Button(activity).apply{text="Book index"}
            body.addView(indexButton)
            indexButton.setOnClickListener{
                worker.execute{
                    val sha=uri.lastPathSegment.orEmpty()
                    val index=NexusBookIndex(activity)
                    val state=runCatching{index.status(sha)}.getOrDefault("Index status unavailable")
                    activity.runOnUiThread{
                        if(!activity.isFinishing&&!activity.isDestroyed){
                            AlertDialog.Builder(activity).setTitle("Private book index").setMessage(state)
                                .setPositiveButton("Close",null)
                                .setNeutralButton("Retry"){_,_->
                                    worker.execute{runCatching{index.retry(sha)}}
                                }.show()
                        }
                    }
                }
            }
        }
        if(NexusArchiveOperation.available(activity,uri)&&
            (type.startsWith("image/")||type.startsWith("video/")||type.startsWith("audio/"))){
            body.addView(android.widget.Button(activity).apply{
                text="Media inspection"
                setOnClickListener{NexusMediaActions.inspect(activity,uri)}
            })
            if(type.startsWith("video/")||type.startsWith("audio/")){
                body.addView(android.widget.Button(activity).apply{
                    text="Extract audio track"
                    setOnClickListener{NexusMediaActions.audio(activity,uri)}
                })
            }
            if(type.startsWith("image/")||type.startsWith("video/")){
                body.addView(android.widget.Button(activity).apply{
                    text=if(type.startsWith("video/"))"Extract video frame" else "Resize or convert"
                    setOnClickListener{NexusMediaActions.derivative(activity,uri,type)}
                })
            }
        }
        var target:Target?=null
        if(type.startsWith("image/")){
            val similar=android.widget.Button(activity).apply{
                text="Find similar artwork";isEnabled=false
            }
            body.addView(similar)
            similar.setOnClickListener{
                val id=target?.coreId?:return@setOnClickListener
                similar.isEnabled=false;similar.text="Searching visual index…"
                worker.execute{
                    val result=runCatching{StandaloneRuntime.nexusVisualSimilar(id,40)}
                    activity.runOnUiThread{
                        if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                        similar.text="Find similar artwork";similar.isEnabled=true
                        result.onSuccess{response->
                            val matches=(response["matches"] as? List<*>)?.mapNotNull{it as? Map<*,*>}.orEmpty()
                            val visible=matches.filter{!it["uri"]?.toString().isNullOrBlank()}
                            val compared=response["compared"] as? Number?:0
                            val heading="Visual matches · "+compared+" indexed images compared"
                            val dialog=AlertDialog.Builder(activity).setTitle(heading)
                                .setPositiveButton("Close",null)
                            if(response["indexed"]!=true){
                                dialog.setMessage("No compatible whole-image descriptor. Analyse this artwork in Core first.")
                            }else if(visible.isEmpty()){
                                dialog.setMessage("No compatible visual matches are available in the analysed library.")
                            }else{
                                val labels=visible.map{row->
                                    val name=row["filename"]?.toString()?:"Artwork"
                                    val score=(row["visual_score"] as? Number)?.toDouble()?:0.0
                                    name+" · cosine "+String.format(java.util.Locale.ROOT,"%.3f",score)
                                }.toTypedArray()
                                dialog.setItems(labels){_,which->
                                    val row=visible[which]
                                    runCatching{NexusFileOpen.open(activity,Uri.parse(row["uri"].toString()),
                                        row["mime_type"]?.toString()?:"image/*",row["filename"]?.toString()?:"Artwork")}
                                        .onFailure{AlertDialog.Builder(activity).setMessage("Artwork could not be opened.")
                                            .setPositiveButton("Close",null).show()}
                                }
                            }
                            dialog.show()
                        }.onFailure{
                            AlertDialog.Builder(activity).setMessage("Visual search unavailable: "+it.message.orEmpty().take(120))
                                .setPositiveButton("Close",null).show()
                        }
                    }
                }
            }
            // The same verified Core identity used for rating controls access
            // to similarity search. Generic files do not invent a Core ID.
            val originalStatus=status
            originalStatus.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                    if(similar.text=="Find similar artwork")similar.isEnabled=target?.coreId!=null
                }
                override fun afterTextChanged(s:android.text.Editable?){}
            })
        }
        var selected:Int?=null
        var saving=false
        val builder=AlertDialog.Builder(activity).setTitle(title?:"File details")
            .setView(android.widget.ScrollView(activity).apply{addView(body)}).setPositiveButton("Save rating",null)
            .setNegativeButton("Close",null)
            .setNeutralButton(if(open==null)"Clear rating" else "Open file",null)
        val dialog=builder.create()
        dialog.setOnShowListener{
            val save=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val neutral=dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            save.isEnabled=false;neutral.isEnabled=open!=null
            fun commit(value:Int){
                val identity=target?:return
                if(saving)return
                saving=true;save.isEnabled=false;neutral.isEnabled=false;slider.isEnabled=false
                status.text="Saving rating…"
                worker.execute{
                    val result=runCatching{write(activity,identity,value)}
                    activity.runOnUiThread{
                        if(!dialog.isShowing||activity.isFinishing)return@runOnUiThread
                        saving=false;slider.isEnabled=true;neutral.isEnabled=true
                        result.onSuccess{
                            selected=null;status.text=if(value<0)"Unrated" else "Rating: "+value+"/100"
                        }.onFailure{
                            status.text="Rating not saved: "+it.message.orEmpty().take(100)
                            save.isEnabled=selected!=null
                        }
                    }
                }
            }
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(bar:SeekBar){}
                override fun onStopTrackingTouch(bar:SeekBar){}
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){
                    if(fromUser&&!saving){
                        selected=value;status.text="Rating: "+value+"/100";save.isEnabled=target!=null
                    }
                }
            })
            save.setOnClickListener{selected?.let(::commit)}
            neutral.setOnClickListener{if(open!=null){dialog.dismiss();open.run()}else commit(-1)}
            worker.execute{
                val result=runCatching{
                    val identity=identify(activity,uri,type)
                    check(identity.sha!=null||identity.coreId!=null){"Verified stable identity unavailable; open a verified offline original"}
                    identity to read(activity,identity)
                }
                activity.runOnUiThread{
                    if(!dialog.isShowing||activity.isFinishing)return@runOnUiThread
                    result.onSuccess{(identity,scores)->
                        target=identity
                        slider.progress=if(scores.manual>=0)scores.manual else scores.pending?:50
                        slider.isEnabled=true;neutral.isEnabled=true
                        status.text=when{
                            scores.pending!=null->"Core rating: "+(if(scores.manual>=0)scores.manual.toString() else "unrated")+
                                "; saved content rating: "+scores.pending+". Select and save to reconcile."
                            scores.manual>=0->"Rating: "+scores.manual+"/100"
                            else->"Unrated"
                        }
                    }.onFailure{status.text="Rating unavailable: "+it.message.orEmpty().take(130)}
                }
            }
        }
        dialog.show()
    }
}
