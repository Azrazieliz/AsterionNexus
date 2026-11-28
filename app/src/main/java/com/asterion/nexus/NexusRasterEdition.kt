package com.asterion.nexus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.AtomicFile
import com.openlocal.office.core.BookArchive
import java.io.*
import java.util.Locale
import java.util.zip.*
import org.json.JSONObject

/** Render a separate annotated EPUB draft. Every page is retained as a
 * bounded raster with region-number markers; translations are adjacent,
 * never painted over the original speech, artwork or layout. */
internal object NexusRasterEdition{
    private const val MAX_PAGES=3000
    private const val MAX_PAGE_BYTES=128*1024*1024
    private const val MAX_TOTAL=900L*1024*1024
    fun supports(mime:String)=mime=="application/pdf"||mime in setOf("application/vnd.comicbook+zip","application/x-cbz")||NexusRarBook.supported(mime)
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun atomic(file:File,bytes:ByteArray){
        val f=AtomicFile(file);val out=f.startWrite()
        try{out.write(bytes);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    private fun bounded(input:InputStream):ByteArray=input.use{
        val out=ByteArrayOutputStream();val b=ByteArray(65536)
        while(true){
            if(Thread.currentThread().isInterrupted)throw InterruptedIOException("Edition paused")
            val n=it.read(b);if(n<0)break;if(n==0)continue
            require(n<=MAX_PAGE_BYTES-out.size()){"Page byte limit"}
            out.write(b,0,n)
        }
        out.toByteArray()
    }
    private fun raster(bytes:ByteArray):Bitmap{
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        require(options.outWidth>0&&options.outHeight>0){"Source page is not decodable"}
        var sample=1
        while(maxOf(options.outWidth,options.outHeight)/sample>2048||
            options.outWidth.toLong()/sample*(options.outHeight.toLong()/sample)>4_194_304L)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888
        })?:error("Page decoding failed")
    }
    private interface Pages:Closeable{val count:Int;fun page(i:Int):Bitmap}
    private fun open(context:Context,item:NexusRegistry.Item):Pages{
        val registry=NexusRegistry(context)
        val uri=NexusLibraryProvider.uriFor(context,item.sha256)
        if(item.mime=="application/pdf"){
            val fd=context.contentResolver.openFileDescriptor(uri,"r")?:error("PDF original unavailable")
            val pdf=try{PdfRenderer(fd)}catch(e:Exception){fd.close();throw e}
            return object:Pages{
                override val count=pdf.pageCount
                override fun page(i:Int):Bitmap=pdf.openPage(i).use{p->
                    val factor=minOf(2048.0/p.width.coerceAtLeast(1),2048.0/p.height.coerceAtLeast(1))
                    val image=Bitmap.createBitmap((p.width*factor).toInt().coerceAtLeast(1),(p.height*factor).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    try{image.eraseColor(Color.WHITE);p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);image}
                    catch(e:Exception){image.recycle();throw e}
                }
                override fun close(){pdf.close()}
            }
        }
        if(NexusRarBook.supported(item.mime)){
            val rar=NexusRarBook(registry.original(item))
            return object:Pages{
                override val count=rar.count()
                override fun page(i:Int)=raster(rar.page(i))
                override fun close(){rar.close()}
            }
        }
        val comic=BookArchive(context,uri,false)
        return object:Pages{
            override val count=comic.imageCount()
            override fun page(i:Int):Bitmap{
                val path=Uri.parse(comic.archiveEntry(i)).encodedPath?:error("Comic page path missing")
                val response=comic.response(path)?:error("Comic page unavailable")
                return raster(bounded(response.data?:error("Comic page data missing")))
            }
            override fun close(){comic.close()}
        }
    }
    private fun pageMarkup(index:Int,result:NexusBookOcr.Result,language:String,translate:(String)->String):String{
        val body=StringBuilder()
        body.append("<?xml version='1.0' encoding='utf-8'?>\n<html xmlns='http://www.w3.org/1999/xhtml' xml:lang='en'><head><title>Page ").append(index+1)
            .append("</title><meta name='viewport' content='width=device-width, initial-scale=1'/><style>body{margin:0;padding:12px;background:#f4f2ed;color:#181a20;font:16px sans-serif}.page{position:relative;width:100%;margin:auto}.page img{display:block;width:100%;height:auto}.marker{position:absolute;background:#202733;color:white;border:1px solid white;padding:1px 3px;border-radius:4px;font:12px sans-serif}.line{border-top:1px solid #aaa;padding:8px 0}.source{font-size:.9em;color:#454953;white-space:pre-wrap}.translated{white-space:pre-wrap}</style></head><body>")
        body.append("<h2>Page ").append(index+1).append(" · English draft</h2><p>Original page retained visually. Numbered regions link to translations below; unresolved text is not erased.</p><div class='page'><img src='page-")
            .append(index).append(".png' alt='Original page ").append(index+1).append("'/>\n")
        for((i,region) in result.regions.withIndex()){
            body.append("<span class='marker' style='left:").append(String.format(Locale.ROOT,"%.4f",region.x*100)).append("%;top:")
                .append(String.format(Locale.ROOT,"%.4f",region.y*100)).append("%'>").append(i+1).append("</span>")
        }
        body.append("</div><p>Detected regions: ").append(result.boxesDetected).append(" · readable coordinates: ").append(result.regions.size).append(".</p>")
        if(result.regions.isEmpty()||result.boxesDetected>result.regions.size)body.append("<p>Some or all text may be unresolved. Review the original page.</p>")
        for((i,region) in result.regions.withIndex()){
            val original=region.text
            val detected=NexusTranslationQueue.language(original)
            val actual=if(NexusTranslationQueue.hasCjk(original)){
                if(detected!=null&&detected!=language&&detected!="zh")"Unresolved mixed-language region"
                else translate(original)
            }else original
            body.append("<div class='line'><strong>").append(i+1).append("</strong><div class='source'>")
                .append(esc(original)).append("</div><div class='translated'>").append(esc(actual)).append("</div></div>")
        }
        body.append("<p>OCR: ").append(esc(result.modelId+" "+result.modelVersion)).append(" · layout and translation quality pending review.</p></body></html>")
        return body.toString()
    }
    fun step(context:Context,item:NexusRegistry.Item,work:File,next:Int,language:String,model:String,
             stop:()->Boolean,translate:(String)->String,checkpoint:(Int,Int)->Unit):File?{
        fun checkStop(){if(stop()||Thread.currentThread().isInterrupted)throw InterruptedException()}
        open(context,item).use{pages->
            val count=pages.count
            require(count in 1..MAX_PAGES&&next in 0..count){"Raster edition page limit"}
            val end=(next+1).coerceAtMost(count)
            for(i in next until end){
                checkStop()
                val bitmap=pages.page(i)
                val png=try{ByteArrayOutputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}}finally{bitmap.recycle()}
                require(png.isNotEmpty()&&png.size<=MAX_PAGE_BYTES){"Raster page too large"}
                val recognized=ByteArrayInputStream(png).use{NexusBookOcr.recognize(context,it)}
                checkStop()
                require(recognized.sourceWidth>0&&recognized.sourceHeight>0&&recognized.regions.size==recognized.linesRecognized){"OCR region geometry unavailable"}
                val markup=pageMarkup(i,recognized,language){value->checkStop();translate(value)}
                val page=File(work,"raster-"+i+".png")
                val text=File(work,"raster-"+i+".xhtml")
                val markupBytes=markup.toByteArray(Charsets.UTF_8)
                val existing=work.listFiles()?.filter{it.name.startsWith("raster-")&&(it.name.endsWith(".png")||it.name.endsWith(".xhtml"))&&it!=page&&it!=text}?.sumOf{it.length()}?:0L
                require(existing+png.size+markupBytes.size<=MAX_TOTAL){"Raster edition expansion budget exceeded"}
                require(work.usableSpace>png.size.toLong()+markupBytes.size+64L*1024*1024){"Insufficient working storage"}
                atomic(page,png);atomic(text,markupBytes)
                atomic(File(work,"raster-"+i+".json"),JSONObject().put("source",item.sha256).put("page",i)
                    .put("model",model).put("ocr",recognized.modelId+":"+recognized.modelVersion)
                    .put("regions",recognized.regions.size).put("detected",recognized.boxesDetected)
                    .put("image_sha256",sha(page)).put("markup_sha256",sha(text)).toString().toByteArray(Charsets.UTF_8))
                checkpoint(i+1,count)
            }
            if(end<count)return null
            checkStop()
            var total=0L
            for(i in 0 until count){
                val png=File(work,"raster-"+i+".png");val xhtml=File(work,"raster-"+i+".xhtml")
                val receipt=JSONObject(File(work,"raster-"+i+".json").readText(Charsets.UTF_8))
                require(receipt.getString("source")==item.sha256&&receipt.getInt("page")==i&&receipt.getString("model")==model&&
                    receipt.getString("image_sha256")==sha(png)&&receipt.getString("markup_sha256")==sha(xhtml)){"Raster checkpoint changed"}
                total+=png.length()+xhtml.length()
                require(total<=MAX_TOTAL){"Raster edition expansion budget exceeded"}
            }
            require(work.usableSpace>total+64L*1024*1024){"Insufficient edition output storage"}
            val out=File(work,"edition.epub")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use{zip->
                fun add(name:String,text:String){zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray(Charsets.UTF_8));zip.closeEntry()}
                val mimetype="application/epub+zip".toByteArray(Charsets.US_ASCII)
                zip.putNextEntry(ZipEntry("mimetype").apply{method=ZipEntry.STORED;size=mimetype.size.toLong();crc=CRC32().apply{update(mimetype)}.value})
                zip.write(mimetype);zip.closeEntry()
                add("META-INF/container.xml","<?xml version='1.0'?><container version='1.0' xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='EPUB/package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
                val manifest=StringBuilder();val spine=StringBuilder();val nav=StringBuilder()
                for(i in 0 until count){
                    checkStop()
                    for((extension,source) in listOf("png" to File(work,"raster-"+i+".png"),"xhtml" to File(work,"raster-"+i+".xhtml"))){
                        zip.putNextEntry(ZipEntry("EPUB/page-"+i+"."+extension));source.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                    }
                    manifest.append("<item id='image").append(i).append("' href='page-").append(i).append(".png' media-type='image/png'/><item id='page").append(i).append("' href='page-").append(i).append(".xhtml' media-type='application/xhtml+xml'/>")
                    spine.append("<itemref idref='page").append(i).append("'/>")
                    nav.append("<li><a href='page-").append(i).append(".xhtml'>Page ").append(i+1).append("</a></li>")
                }
                add("EPUB/nav.xhtml","<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Contents</title></head><body><nav epub:type='toc'><ol>"+nav+"</ol></nav></body></html>")
                val stamp=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                add("EPUB/package.opf","<?xml version='1.0'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>urn:sha256:"+item.sha256+"</dc:identifier><dc:title>"+esc(item.displayName+" — English annotated draft")+"</dc:title><dc:language>en</dc:language><meta property='dcterms:modified'>"+stamp+"</meta></metadata><manifest><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>"+manifest+"</manifest><spine>"+spine+"</spine></package>")
            }
            val verified=NexusZipExtraction.inspect(out)
            require(verified.files.size==4+count*2){"Edition ZIP member count mismatch"}
            ZipFile(out).use{zip->
                for(i in 0 until count){
                    checkStop()
                    for((ext,file) in listOf("png" to File(work,"raster-"+i+".png"),"xhtml" to File(work,"raster-"+i+".xhtml"))){
                        val entry=zip.getEntry("EPUB/page-"+i+"."+ext)?:error("Edition page missing")
                        require(entry.size==file.length()&&zip.getInputStream(entry).use{sha(it)}==sha(file)){"Edition page readback mismatch"}
                    }
                }
            }
            return out
        }
    }
    private fun sha(file:File)=file.inputStream().use{sha(it)}
    private fun sha(input:InputStream):String{
        val md=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val n=input.read(buffer);if(n<0)break;if(n>0)md.update(buffer,0,n)}
        return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
