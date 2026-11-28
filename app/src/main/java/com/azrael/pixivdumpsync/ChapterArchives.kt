package com.azrael.pixivdumpsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Each page is cached on disk before ZIP assembly; never marks partial ZIP as complete. */
object ChapterArchives {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val pauses=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    fun pause(context:Context,id:Long){
        pauses.add(id)
        ChapterQueue(context).use{it.pause(id)}
    }
    fun resume(context:Context,id:Long){
        pauses.remove(id)
        ChapterQueue(context).use{it.retry(id)}
        start(context)
    }
    private const val CHANNEL="asterion_chapter_transfers"
    fun start(context:Context){
        if(!AppPower.enabled(context))return
        val intent=Intent(context,ChapterArchiveService::class.java)
        try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)
            else context.startService(intent)
        }catch(_:Exception){
            schedule(context)
        }
    }
    /** The OS may prohibit foreground-service starts from backfill callbacks.
     * Keep the already-persisted chapter queue alive through JobScheduler.
     */
    fun schedule(context:Context):Boolean{
        if(!AppPower.enabled(context))return false
        val scheduler=context.getSystemService(android.app.job.JobScheduler::class.java)
        val job=android.app.job.JobInfo.Builder(26482,
            android.content.ComponentName(context,ChapterArchiveJobService::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1500L).setOverrideDeadline(15000L)
            .build()
        return scheduler.schedule(job)>0
    }
    fun notification(context:Context,message:String):Notification{
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Chapter downloads",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,
            Intent(context,com.asterion.nexus.NexusPremiumHomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context,CHANNEL)
            .setContentTitle("Nexus · Comics")
            .setContentText(message.take(95))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"STOP ALL BACKFILL",
                EmergencyBackfillStopReceiver.action(context)).build())
            .build()
    }
    fun run(context:Context,onStatus:(String)->Unit)=executor.submit{
        if(!AppPower.enabled(context))return@submit
        if(!busy.compareAndSet(false,true))return@submit
        val agents=Executors.newFixedThreadPool(2){r->
            Thread(r,"asterion-comics-zip-original-agent").apply{isDaemon=true}
        }
        try{
            ChapterQueue(context).use{db->
                db.recover()
                db.retryDueFailures()
            }
            val jobs=(0 until 2).map{
                agents.submit {
                    ChapterQueue(context).use{db->
                var completedThisPass=0
                val deadline=android.os.SystemClock.elapsedRealtime()+5L*60_000L
                while(AppPower.enabled(context) &&
                    !Thread.currentThread().isInterrupted &&
                    completedThisPass<2 &&
                    android.os.SystemClock.elapsedRealtime()<deadline){
                    completedThisPass++
                    val job=db.claimNext()?:break
                    try{
                        val chapters=ChapterScraper.chapters(job.url,context)
                        val signature=MessageDigest.getInstance("SHA-256")
                            .digest(chapters.joinToString("\n"){
                                it.url+"\t"+it.title
                            }.toByteArray(Charsets.UTF_8))
                            .joinToString(""){"%02x".format(it.toInt() and 255)}
                        val earlier=db.savedSignature(job.id)
                        val preserved=earlier!=null && earlier.second==signature &&
                            Uri.parse(earlier.first).authority==context.packageName+".library" &&
                            runCatching{
                                context.contentResolver.openAssetFileDescriptor(
                                    Uri.parse(earlier.first),"r")?.use{
                                    it.length!=0L
                                }?:false
                            }.getOrDefault(false)
                        val uri=if(preserved)earlier!!.first else
                            build(context,job,chapters){pct->
                                db.update(job.id,"working",pct)
                                onStatus("${job.title}: $pct%")
                            }
                        if(pauses.contains(job.id))
                            throw java.util.concurrent.CancellationException("Archive paused")
                        db.completeCheck(job.id,uri,signature)
                        val item=com.asterion.nexus.NexusRegistry(context).find(Uri.parse(uri).lastPathSegment)
                        if(item!=null){
                            com.asterion.nexus.NexusCloudPublication.enqueueIfConnected(context,item)
                            onStatus(com.asterion.nexus.NexusCloudPublication.status(context,item))
                        }else onStatus("Local archive verified; cloud receipt unavailable")
                    }catch(t:Exception){
                        if(pauses.remove(job.id) ||
                            t is java.util.concurrent.CancellationException){
                            db.pause(job.id)
                            onStatus("Paused archive "+job.title)
                        }else{
                            db.update(job.id,"error",job.progress,
                                t.message?:"Unknown failure")
                            onStatus("Failed "+job.title)
                        }
                    }
                }
                    }
                }
            }
            for(job in jobs)runCatching{job.get()}
        }finally{
            agents.shutdownNow()
            busy.set(false)
        }
    }
    private fun safeName(value:String):String=value.replace(
        Regex("[^\\p{L}\\p{N} ._-]"),"_").take(85).ifBlank{"Archive"}
    private fun download(context:Context,url:String,referer:String,target:File,
                         stop:()->Boolean){
        val partial=File(target.parentFile,target.name+".part")
        var cause:Exception?=null
        for(attempt in 0 until 4){
            if(stop())throw java.util.concurrent.CancellationException("Archive paused")
            try{
                val conn=URL(url).openConnection().apply{
                    connectTimeout=25000;readTimeout=45000
                    setRequestProperty("User-Agent",
                        runCatching{android.webkit.WebSettings.getDefaultUserAgent(context)}
                            .getOrDefault(
                            "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36"))
                    setRequestProperty("Referer",referer)
                    com.azrael.galleryflow.SiteSession.cookie(url)?.let {
                        setRequestProperty("Cookie",it)
                    }
                }
                if(conn is java.net.HttpURLConnection){
                    when(conn.responseCode){
                        401,403->throw IllegalStateException(
                            "Image host HTTP "+conn.responseCode+
                                ": authorized account session or native parser required")
                        404,410->throw IllegalStateException(
                            "Image unavailable HTTP "+conn.responseCode+
                                ": missing chapter image")
                        429->throw IllegalStateException(
                            "Image host HTTP 429: temporarily rate-limited")
                    }
                }
                val mediaType=conn.contentType.orEmpty().substringBefore(';').lowercase()
                require(mediaType.startsWith("image/") ||
                    mediaType=="application/octet-stream"){
                    "Image URL did not return an image: ${conn.contentType}"}
                conn.getInputStream().buffered().use{input->
                    FileOutputStream(partial).use{output->
                        val buffer=ByteArray(128*1024)
                        while(true){
                            if(stop())throw java.util.concurrent.CancellationException(
                                "Archive paused")
                            val n=input.read(buffer)
                            if(n<0)break
                            output.write(buffer,0,n)
                        }
                    }
                }
                require(partial.length()>128){"Image response was empty or truncated"}
                val expected=conn.contentLengthLong
                require(expected<=0 || expected==partial.length()){
                    "Image response was truncated: "+partial.length()+" / "+expected
                }
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(partial.absolutePath,options)
                require(options.outWidth>0 && options.outHeight>0){
                    "Image response contained non-image content"
                }
                require(partial.renameTo(target)){"Cannot commit cached page"}
                return
            }catch(t:Exception){
                if(t is java.util.concurrent.CancellationException)throw t
                cause=t;partial.delete()
                if(t.message.orEmpty().contains("403") &&
                    com.azrael.galleryflow.SiteSession.bound(context,referer)){
                    try {
                        BrowserImageFetch.download(context,url,referer,partial)
                        val bounds=android.graphics.BitmapFactory.Options().apply{
                            inJustDecodeBounds=true
                        }
                        android.graphics.BitmapFactory.decodeFile(partial.absolutePath,bounds)
                        require(bounds.outWidth>0&&bounds.outHeight>0) {
                            "Browser delivered an invalid image"
                        }
                        require(partial.renameTo(target)) {
                            "Unable to save verified browser image"
                        }
                        return
                    }catch(browserError:Exception){
                        partial.delete()
                        throw IllegalStateException(
                            "Browser image retrieval failed: "+browserError.message,browserError)
                    }
                }
                val terminal=t.message.orEmpty().let{message->
                    Regex("(?i)\\b(?:401|403|404|410|429)\\b|"+
                        "did not return an image|non-image|invalid image")
                        .containsMatchIn(message)
                }
                if(terminal)break
                if(attempt<3)Thread.sleep((attempt+1)*750L)
            }
        }
        throw cause?:IllegalStateException("Unable to retrieve image")
    }
    private fun build(context:Context,task:ChapterTask,chapters:List<ChapterLink>,
                      onProgress:(Int)->Unit):String{
        val base=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?:error("External app storage is unavailable")
        val cache=File(base,"chapter-cache/${task.id}")
        require(cache.isDirectory||cache.mkdirs()){"Unable to create page cache"}
        require(chapters.isNotEmpty()){"No chapters discovered"}
        val out=File(cache,"complete.cbz.part")
        try{
            var expectedPages=0
            val chapterManifest=org.json.JSONArray()
            ZipOutputStream(FileOutputStream(out)).use{zip->
                for((chapterIndex,chapter) in chapters.withIndex()){
                    if(pauses.contains(task.id))
                        throw java.util.concurrent.CancellationException("Archive paused")
                    val images=ChapterScraper.pageImages(chapter.url,context)
                    require(images.isNotEmpty()){"No complete pages for "+chapter.title}
                    val pageManifest=org.json.JSONArray()
                    for((pageIndex,image) in images.withIndex()){
                        if(pauses.contains(task.id))
                            throw java.util.concurrent.CancellationException("Archive paused")
                        val ext=Regex("(?i)\\.(jpe?g|png|webp|gif|avif)([?#]|$)")
                            .find(image)?.groupValues?.get(1)?.lowercase()?:"jpg"
                        val page=File(cache,"%05d_%05d.%s".format(chapterIndex+1,pageIndex+1,ext))
                        if(!page.isFile||page.length()<128)
                            download(context,image,chapter.url,page){
                                pauses.contains(task.id)||!AppPower.enabled(context)
                            }
                        val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        android.graphics.BitmapFactory.decodeFile(page.absolutePath,bounds)
                        require(bounds.outWidth>0&&bounds.outHeight>0){"Invalid cached comic page"}
                        val actualExt=when(bounds.outMimeType){
                            "image/png"->"png";"image/jpeg"->"jpg";"image/webp"->"webp"
                            "image/gif"->"gif";"image/avif"->"avif"
                            else->throw IllegalStateException("Unsupported comic image format")
                        }
                        // Flat natural page ordering; actual image format is retained.
                        val name="%05d_%s_%05d.%s".format(java.util.Locale.ROOT,chapterIndex+1,
                            safeName(chapter.title),pageIndex+1,actualExt)
                        zip.putNextEntry(ZipEntry(name))
                        page.inputStream().buffered().use{it.copyTo(zip,128*1024)}
                        zip.closeEntry()
                        expectedPages++
                        pageManifest.put(org.json.JSONObject().put("page",pageIndex+1).put("entry",name)
                            .put("source_id",com.asterion.nexus.NexusOriginalStore.sourceKey(image)))
                    }
                    chapterManifest.put(org.json.JSONObject().put("chapter",chapterIndex+1).put("title",chapter.title)
                        .put("source_id",com.asterion.nexus.NexusOriginalStore.sourceKey(chapter.url)).put("pages",pageManifest))
                    onProgress((chapterIndex+1)*90/chapters.size)
                }
                zip.putNextEntry(ZipEntry("nexus-comic-manifest.json"))
                zip.write(org.json.JSONObject().put("version",1).put("title",task.title)
                    .put("series_source_id",com.asterion.nexus.NexusOriginalStore.sourceKey(task.url))
                    .put("chapters",chapterManifest).put("page_count",expectedPages).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            require(out.length()>100){"Incomplete ZIP archive"}
            out.inputStream().use{
                ArchiveIntegrity.inspect(it,"ZIP",expectedImages=expectedPages)
            }
            val result=out.inputStream().use{input->
                com.asterion.nexus.NexusRegistry(context).ingest(input,safeName(task.title)+".cbz",
                    "application/vnd.comicbook+zip","nexus-comic","owner",task.url,out.length(),null)
            }
            val uri=com.asterion.nexus.NexusLibraryProvider.uriFor(context,result.sha256)
            cache.listFiles()?.forEach{it.delete()}
            cache.delete()
            onProgress(100)
            return uri.toString()
        }catch(t:Exception){
            out.delete() // Complete page files survive retry and application restart.
            throw t
        }
    }
}

class ChapterArchiveService:Service(){
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        try {
            startForeground(25481,ChapterArchives.notification(this,"Preparing archive"))
        }catch(t:RuntimeException){
            android.util.Log.e("AsterionChapters",
                "Foreground promotion denied; chapter ZIP queue remains saved",t)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val running=try{
            ChapterArchives.run(applicationContext){message->
                getSystemService(NotificationManager::class.java)
                    .notify(25481,ChapterArchives.notification(this,message))
            }
        }catch(t:RuntimeException){
            android.util.Log.e("AsterionChapters",
                "Archive worker could not start; saved chapters unchanged",t)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        Thread{runCatching{running.get()};stopSelfResult(startId)}.start()
        return START_REDELIVER_INTENT
    }
}
