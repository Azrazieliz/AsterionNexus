package com.asterion.nexus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.util.AtomicFile
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.*
import java.nio.charset.CodingErrorAction
import java.util.zip.*
import java.util.concurrent.atomic.AtomicBoolean

/** Durable offline translation queue. Output is always an independent verified draft.
 * Never sends owner source text off-device; never labels a partial job complete.
 * PDF/comic layout reconstruction is deliberately gated on OCR region evidence. */
class NexusTranslationQueue(context:Context){
    private val app=context.applicationContext
    private val registry=NexusRegistry(app)
    private class DB(c:Context):SQLiteOpenHelper(c,"nexus_translation_v1.db",null,1){
        override fun onCreate(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE jobs(sha TEXT PRIMARY KEY,mime TEXT NOT NULL,language TEXT NOT NULL,state TEXT NOT NULL,error TEXT,next INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,model TEXT,output TEXT,updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX translation_pending ON jobs(state,updated)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){error("No destructive migration")}
    }
    private data class Work(val sha:String,val mime:String,val language:String,val next:Int,val model:String?)
    companion object{
        const val JOB_ID=28173
        internal fun language(text:String):String?{
            var kana=0;var hangul=0;var han=0
            text.codePoints().forEach{c->
                when(Character.UnicodeScript.of(c)){
                    Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA->kana++
                    Character.UnicodeScript.HANGUL->hangul++
                    Character.UnicodeScript.HAN->han++
                    else->Unit
                }
            }
            if(kana>0&&hangul>0)return null
            return when{kana>0->"ja";hangul>0->"ko";han>=2->"zh";else->null}
        }
        internal fun hasCjk(text:String):Boolean=text.codePoints().anyMatch{
            Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN,Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,Character.UnicodeScript.HANGUL)
        }
    }
    fun enqueue(sha:String,mime:String,sample:String){
        if(!hasCjk(sample))return
        val language=language(sample)
        val state=when{
            language==null->"language_review"
            mime !in setOf("text/plain","application/epub+zip")->"blocked_layout"
            else->"queued"
        }
        val helper=DB(app)
        try{
            val row=ContentValues().apply{
                put("sha",sha);put("mime",mime);put("language",language?:"und")
                put("state",state);put("updated",System.currentTimeMillis())
                if(state=="blocked_layout")put("error","A layout-preserving native edition adapter is not yet validated")
                if(state=="language_review")put("error","Mixed or ambiguous CJK language")
            }
            helper.writableDatabase.insertWithOnConflict("jobs",null,row,SQLiteDatabase.CONFLICT_IGNORE)
        }finally{helper.close()}
        if(state=="queued")schedule()
    }
    fun schedule(continuation:Boolean=false){
        val scheduler=app.getSystemService(JobScheduler::class.java)?:return
        if(!continuation&&scheduler.getPendingJob(JOB_ID)!=null)return
        scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(app,NexusTranslationJob::class.java))
            .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).setMinimumLatency(5000L).build())
    }
    fun status(sha:String):String{
        val helper=DB(app)
        try{helper.readableDatabase.rawQuery("SELECT state,next,total,error,output FROM jobs WHERE sha=?",arrayOf(sha)).use{c->
            if(!c.moveToFirst())return "Awaiting private text index / CJK language evidence"
            return c.getString(0)+" · "+c.getInt(1)+" / "+c.getInt(2)+
                (c.getString(3)?.let{" · "+it}?:"")+(c.getString(4)?.let{" · Independent edition registered"}?:"")
        }}finally{helper.close()}
    }
    fun retry(sha:String){
        val helper=DB(app)
        try{helper.writableDatabase.execSQL("UPDATE jobs SET state='queued',error=NULL,updated=? WHERE sha=? AND state IN ('blocked_engine','failed','paused')",arrayOf(System.currentTimeMillis(),sha))}
        finally{helper.close()}
        schedule()
    }
    fun pause(sha:String){
        val helper=DB(app)
        try{helper.writableDatabase.execSQL("UPDATE jobs SET state='paused',updated=? WHERE sha=? AND state IN ('queued','running')",arrayOf(System.currentTimeMillis(),sha))}
        finally{helper.close()}
    }
    private fun pending():Work?{
        val helper=DB(app)
        try{helper.readableDatabase.rawQuery("SELECT sha,mime,language,next,model FROM jobs WHERE state IN ('queued','running') ORDER BY updated ASC LIMIT 1",null).use{c->
            return if(c.moveToFirst())Work(c.getString(0),c.getString(1),c.getString(2),c.getInt(3),c.getString(4))else null
        }}finally{helper.close()}
    }
    private fun paused(sha:String):Boolean{
        val helper=DB(app)
        try{helper.readableDatabase.rawQuery("SELECT state FROM jobs WHERE sha=?",arrayOf(sha)).use{return !it.moveToFirst()||it.getString(0)=="paused"}}
        finally{helper.close()}
    }
    private fun update(sha:String,state:String,error:String?=null,next:Int?=null,total:Int?=null,model:String?=null,output:String?=null){
        val helper=DB(app)
        try{val row=ContentValues().apply{
            put("state",state);put("error",error);put("updated",System.currentTimeMillis())
            next?.let{put("next",it)};total?.let{put("total",it)};model?.let{put("model",it)};output?.let{put("output",it)}
        };helper.writableDatabase.update("jobs",row,"sha=? AND state<>'paused'",arrayOf(sha))}
        finally{helper.close()}
    }
    private fun utf8(bytes:ByteArray):String=Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    private fun translated(text:String,engine:NexusMarianEngine,stop:()->Boolean):String{
        if(!hasCjk(text))return text
        // Preserve line and paragraph delimiters and bound context by sentences.
        // Oversized sentence fails closed rather than silently truncating it.
        val segments=Regex("(?<=[。！？!?\\n])").split(text)
        return segments.joinToString(""){segment->
            if(stop())throw InterruptedException()
            if(!hasCjk(segment))segment
            else{
                val prefix=segment.takeWhile{it.isWhitespace()}
                val suffix=segment.takeLastWhile{it.isWhitespace()}
                val core=segment.trim()
                val out=engine.translate(core,stop)
                prefix+out+suffix
            }
        }
    }
    private fun atomicText(file:File,text:String){
        val f=AtomicFile(file);val out=f.startWrite()
        try{out.write(text.toByteArray(Charsets.UTF_8));f.finishWrite(out)}
        catch(e:Exception){f.failWrite(out);throw e}
    }
    private fun checkStop(stop:()->Boolean){if(stop()||Thread.currentThread().isInterrupted)throw InterruptedException()}
    private fun txt(source:File,work:File,p:Work,engine:NexusMarianEngine,stop:()->Boolean):File?{
        require(source.length() in 1..(32L*1024*1024)){"Text exceeds offline adapter budget"}
        val text=utf8(source.readBytes())
        // Every delimiter is retained as a distinct span.
        val paragraphs=Regex("(?<=\\n)").split(text)
        require(paragraphs.size<=200000){"Text section budget exceeded"}
        val limit=(p.next+16).coerceAtMost(paragraphs.size)
        for(i in p.next until limit){
            checkStop(stop)
            atomicText(File(work,"part-"+i),translated(paragraphs[i],engine,stop))
            update(p.sha,"running",next=i+1,total=paragraphs.size,model=engine.identity)
        }
        if(limit<paragraphs.size)return null
        val out=File(work,"edition.txt")
        FileOutputStream(out).use{stream->
            for(i in paragraphs.indices){checkStop(stop);File(work,"part-"+i).inputStream().use{it.copyTo(stream)}}
            stream.fd.sync()
        }
        return out
    }
    private fun epub(source:File,work:File,p:Work,engine:NexusMarianEngine,stop:()->Boolean):File?{
        val plan=NexusZipExtraction.inspect(source)
        require(plan.files.size<=10000)
        // Do not touch signed/encrypted EPUBs, scripts or external entities.
        require(plan.files.none{it.path in setOf("META-INF/encryption.xml","META-INF/signatures.xml")}){"Protected EPUB cannot be translated"}
        ZipFile(source).use{zip->
            val content=plan.files.filter{it.path.endsWith(".xhtml",true)||it.path.endsWith(".html",true)||it.path.endsWith(".htm",true)}
            require(content.isNotEmpty()){"EPUB contains no supported markup"}
            val limit=(p.next+1).coerceAtMost(content.size)
            for(i in p.next until limit){
                checkStop(stop)
                val entry=content[i]
                require(entry.length<=8*1024*1024){"EPUB chapter exceeds translation budget"}
                val sourceMarkup=zip.getInputStream(zip.getEntry(entry.path)).use{utf8(it.readBytes())}
                require(!sourceMarkup.contains("<!ENTITY",true)){"External/entity declarations unsupported"}
                val doc=Jsoup.parse(sourceMarkup,"",Parser.xmlParser())
                doc.outputSettings().prettyPrint(false)
                // Preserve all elements, attributes, links, images and style.
                // Only actual readable text nodes change; structural ruby annotations remain.
                val nodes=doc.getAllElements().flatMap{it.textNodes()}.filter{node->
                    generateSequence(node.parent()){it.parent()}.none{parent->
                        parent.nodeName().lowercase() in setOf("script","style","code","pre","rt","rp")
                    }
                }
                for(node in nodes){
                    checkStop(stop)
                    val value=node.getWholeText()
                    if(hasCjk(value))node.text(translated(value,engine,stop))
                }
                atomicText(File(work,"part-"+i),doc.outerHtml())
                update(p.sha,"running",next=i+1,total=content.size,model=engine.identity)
            }
            if(limit<content.size)return null
            val out=File(work,"edition.epub")
            val translated=content.mapIndexed{index,e->e.path to File(work,"part-"+index)}.toMap()
            require(zip.getEntry("mimetype")!=null){"EPUB mimetype missing"}
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use{dest->
                val mime=zip.getInputStream(zip.getEntry("mimetype")).use{it.readBytes()}
                require(String(mime,Charsets.US_ASCII)=="application/epub+zip"){"Invalid EPUB mimetype"}
                val first=ZipEntry("mimetype").apply{
                    method=ZipEntry.STORED;size=mime.size.toLong();crc=CRC32().apply{update(mime)}.value
                }
                dest.putNextEntry(first);dest.write(mime);dest.closeEntry()
                for(entry in plan.files){
                    checkStop(stop)
                    if(entry.path=="mimetype")continue
                    dest.putNextEntry(ZipEntry(entry.path))
                    val changed=translated[entry.path]
                    if(changed!=null)changed.inputStream().use{it.copyTo(dest)}
                    else zip.getInputStream(zip.getEntry(entry.path)).use{it.copyTo(dest)}
                    dest.closeEntry()
                }
            }
            // Validate archive independently and ensure non-text member bytes are unchanged.
            val result=NexusZipExtraction.inspect(out)
            require(result.files.map{it.path}.toSet()==plan.files.map{it.path}.toSet()){"EPUB output member mismatch"}
            val originals=plan.files.associateBy{it.path}
            for(e in result.files)if(e.path !in translated){
                val old=originals.getValue(e.path)
                require(e.crc==old.crc&&e.length==old.length){"EPUB resource bytes changed"}
            }
            return out
        }
    }
    fun next(stop:AtomicBoolean):Boolean{
        val job=pending()?:return false
        val shouldStop={stop.get()||Thread.currentThread().isInterrupted||paused(job.sha)}
        val item=registry.find(job.sha)
        if(item==null){update(job.sha,"failed","Verified source missing");return pending()!=null}
        val engine=try{NexusMarianEngine.open(app,job.language)}
        catch(e:Exception){update(job.sha,"blocked_engine","Pinned offline model unavailable or failed initialization");return pending()!=null}
        catch(e:LinkageError){update(job.sha,"blocked_engine","Native tokenizer unavailable");return pending()!=null}
        try{engine.use{
            checkStop(shouldStop)
            require(registry.verifyOriginal(item)){"Original source integrity failure"}
            require(job.model==null||job.model==engine.identity){"Model changed; review existing checkpoints"}
            val work=File(app.noBackupFilesDir,"nexus-translations/"+job.sha).apply{check(isDirectory||mkdirs()||isDirectory)}
            update(job.sha,"running",model=engine.identity)
            val source=registry.original(item)
            val output=when(job.mime){
                "text/plain"->txt(source,work,job,engine,shouldStop)
                "application/epub+zip"->epub(source,work,job,engine,shouldStop)
                else->error("Unsupported layout adapter")
            }
            if(output!=null){
                checkStop(shouldStop)
                require(registry.verifyOriginal(item)&&output.length()>0){"Source or edition integrity failure"}
                val ext=if(job.mime=="text/plain")".txt" else ".epub"
                val title=item.displayName.substringBeforeLast('.')+" — English draft"+ext
                val edition=output.inputStream().use{registry.ingest(it,title,job.mime,"nexus-translation","device",
                    item.sha256+":"+engine.identity+":draft-v1",output.length(),null)}
                require(registry.verifyOriginal(edition))
                registry.linkDerivative(edition.sha256,item.sha256,"translated_from",engine.identity)
                update(job.sha,"draft_verified","English draft; native fidelity and layout QA pending",output=edition.sha256)
                work.deleteRecursively()
            }else update(job.sha,"queued")
        }}catch(e:InterruptedException){update(job.sha,"queued","Interrupted safely; will resume")}
        catch(e:Exception){update(job.sha,"failed",e.message?.take(160)?:e.javaClass.simpleName)}
        return pending()!=null
    }
}
