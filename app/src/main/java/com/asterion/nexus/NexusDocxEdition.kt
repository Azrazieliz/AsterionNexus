package com.asterion.nexus

import android.util.AtomicFile
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.*
import org.xml.sax.InputSource
import org.json.JSONObject

/** A translated draft edits only readable w:t runs in a separate DOCX.
 * Numbering, drawings, fields, layout attributes and relationship targets
 * remain structurally intact. Translation can change text wrapping. */
internal object NexusDocxEdition{
    const val MIME="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val W="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val STRICT_W="http://purl.oclc.org/ooxml/wordprocessingml/main"
    private const val XML="http://www.w3.org/XML/1998/namespace"
    private const val MAX_PART=16*1024*1024
    private val parts=Regex("word/(document|header[0-9]+|footer[0-9]+|footnotes|endnotes|comments)\\.xml")
    private fun supported(n:Node)=n.namespaceURI in setOf(W,STRICT_W)
    private fun document(bytes:ByteArray):Document{
        require(bytes.size in 1..MAX_PART){"Word XML part exceeds budget"}
        // Only XML encodings used by normal OOXML documents. Block DTDs and
        // entity declarations before parsing, independently of SAX flags.
        val charset=when{
            bytes.size>=2&&((bytes[0].toInt() and 255)==255&&(bytes[1].toInt() and 255)==254||
                (bytes[0].toInt() and 255)==254&&(bytes[1].toInt() and 255)==255)->Charsets.UTF_16
            bytes.size>=2&&bytes[0]==0.toByte()&&bytes[1]==60.toByte()->Charsets.UTF_16BE
            bytes.size>=2&&bytes[0]==60.toByte()&&bytes[1]==0.toByte()->Charsets.UTF_16LE
            else->Charsets.UTF_8
        }
        val text=charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text&&!text.contains("<!DOCTYPE")&&!text.contains("<!ENTITY")){"Unsafe Word XML"}
        val f=DocumentBuilderFactory.newInstance()
        f.isNamespaceAware=true
        f.isExpandEntityReferences=false
        f.setFeature("http://xml.org/sax/features/external-general-entities",false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        val b=f.newDocumentBuilder()
        b.setEntityResolver{_,_->throw org.xml.sax.SAXException("External entities prohibited")}
        return b.parse(InputSource(StringReader(text)))
    }
    private fun elements(parent:Node):List<Element>{
        val list=ArrayList<Element>()
        var c=parent.firstChild
        while(c!=null){if(c.nodeType==Node.ELEMENT_NODE)list.add(c as Element);c=c.nextSibling}
        return list
    }
    private fun paragraphs(doc:Document):List<Element>{
        val list=ArrayList<Element>()
        // DOM wildcard traversal preserves actual body, tables and textbox order.
        val all=doc.getElementsByTagNameNS("*","p")
        require(all.length<=200000){"Paragraph count exceeds budget"}
        for(i in 0 until all.length){
            val p=all.item(i) as Element
            if(supported(p))list.add(p)
        }
        return list
    }
    private fun readableRun(run:Element):List<Element>?{
        if(!supported(run)||run.localName!="r")return null
        val children=elements(run)
        if(children.any{!supported(it)||it.localName !in setOf("rPr","t")})return null
        return children.filter{it.localName=="t"}.takeIf{it.isNotEmpty()}
    }
    private fun properties(run:Element):Element?=elements(run).firstOrNull{supported(it)&&it.localName=="rPr"}
    private fun same(a:Element?,b:Element?)=if(a==null)b==null else b!=null&&a.isEqualNode(b)
    /** Adjacent identical-format runs are translated together, preventing
     * artificial token boundaries introduced by Word spellchecking edits. */
    private fun translateContainer(container:Element,stop:()->Boolean,translate:(String)->String){
        val group=ArrayList<Element>()
        var style:Element?=null
        fun flush(){
            if(group.isEmpty())return
            if(stop())throw InterruptedException()
            val original=group.joinToString(""){it.textContent}
            if(NexusTranslationQueue.hasCjk(original)){
                val changed=translate(original)
                require(changed.isNotBlank()){"Empty DOCX translation"}
                // All runs in this group have identical formatting. Leave
                // the nodes in place so their attributes/relationships survive.
                group[0].textContent=changed
                if(changed.firstOrNull()?.isWhitespace()==true||changed.lastOrNull()?.isWhitespace()==true)
                    group[0].setAttributeNS(XML,"xml:space","preserve")
                for(i in 1 until group.size)group[i].textContent=""
            }
            group.clear();style=null
        }
        for(child in elements(container)){
            if(stop())throw InterruptedException()
            val runs=readableRun(child)
            if(runs!=null){
                val p=properties(child)
                if(group.isNotEmpty()&&!same(style,p))flush()
                if(group.isEmpty())style=p
                group.addAll(runs)
            }else{
                flush()
                // Explicit hyperlinks preserve target and boundaries. Field
                // codes, tracked revisions, SDT content and hidden text are
                // not rewritten by a generic text adapter.
                if(supported(child)&&child.localName=="hyperlink")translateContainer(child,stop,translate)
            }
        }
        flush()
    }
    internal fun rewrite(bytes:ByteArray,stop:()->Boolean,translate:(String)->String):ByteArray{
        val doc=document(bytes)
        for(p in paragraphs(doc)){
            if(stop())throw InterruptedException()
            // Never change a field-result paragraph or a tracked deletion.
            var ancestor:Node?=p.parentNode
            var excluded=false
            while(ancestor!=null){
                if(supported(ancestor)&&ancestor.localName in setOf("del","moveFrom","ins","moveTo","fldSimple","sdt")){excluded=true;break}
                ancestor=ancestor.parentNode
            }
            if(excluded)continue
            val field=elements(p).any{r->elements(r).any{supported(it)&&it.localName in setOf("fldChar","instrText")}}
            if(!field)translateContainer(p,stop,translate)
        }
        val factory=TransformerFactory.newInstance()
        val transformer=factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT,"no")
        val out=ByteArrayOutputStream()
        transformer.transform(DOMSource(doc),StreamResult(out))
        val result=out.toByteArray()
        require(result.size<=MAX_PART){"Translated Word XML exceeds budget"}
        // Verify well-formedness before checkpointing.
        document(result)
        return result
    }
    private fun sha(input:InputStream):String{
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true){val n=input.read(buffer);if(n<0)break;if(n>0)md.update(buffer,0,n)}
        return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun sha(file:File)=file.inputStream().use{sha(it)}
    private fun atomic(file:File,data:ByteArray){
        val f=AtomicFile(file);val out=f.startWrite()
        try{out.write(data);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    fun step(source:File,work:File,next:Int,sourceSha:String,model:String,stop:()->Boolean,
             translate:(String)->String,checkpoint:(Int,Int)->Unit):File?{
        val plan=NexusZipExtraction.inspect(source)
        require(plan.files.none{it.path.startsWith("_xmlsignatures/",true)||it.path.endsWith("vbaProject.bin",true)}){"Signed or macro-enabled documents unsupported"}
        val selected=plan.files.filter{parts.matches(it.path)}
        require(selected.any{it.path=="word/document.xml"}&&next in 0..selected.size){"Word document part missing"}
        ZipFile(source).use{zip->
            val end=(next+1).coerceAtMost(selected.size)
            for(i in next until end){
                if(stop())throw InterruptedException()
                val part=selected[i]
                require(part.length in 1..MAX_PART.toLong()){"Word XML part exceeds budget"}
                val bytes=zip.getInputStream(zip.getEntry(part.path)).use{it.readBytes()}
                require(bytes.size.toLong()==part.length){"Word XML length changed"}
                val rewritten=rewrite(bytes,stop,translate)
                val file=File(work,"docx-"+i+".xml")
                atomic(file,rewritten)
                atomic(File(work,"docx-"+i+".json"),JSONObject().put("source",sourceSha).put("model",model)
                    .put("part",part.path).put("sha256",sha(file)).toString().toByteArray(Charsets.UTF_8))
                checkpoint(i+1,selected.size)
            }
            if(end<selected.size)return null
            val changes=selected.mapIndexed{i,p->
                val file=File(work,"docx-"+i+".xml")
                val j=JSONObject(File(work,"docx-"+i+".json").readText(Charsets.UTF_8))
                require(j.getString("source")==sourceSha&&j.getString("model")==model&&j.getString("part")==p.path&&j.getString("sha256")==sha(file)){"Word checkpoint changed"}
                p.path to file
            }.toMap()
            require(work.usableSpace>plan.bytes+64L*1024*1024){"Insufficient edition storage"}
            val out=File(work,"edition.docx")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use{dest->
                for(part in plan.files){
                    if(stop())throw InterruptedException()
                    dest.putNextEntry(ZipEntry(part.path))
                    val changed=changes[part.path]
                    if(changed!=null)changed.inputStream().use{it.copyTo(dest)}
                    else zip.getInputStream(zip.getEntry(part.path)).use{it.copyTo(dest)}
                    dest.closeEntry()
                }
            }
            val verified=NexusZipExtraction.inspect(out)
            require(verified.files.map{it.path}.toSet()==plan.files.map{it.path}.toSet()){"Word package member mismatch"}
            ZipFile(out).use{result->
                for(part in plan.files){
                    if(stop())throw InterruptedException()
                    val changed=changes[part.path]
                    val expected=if(changed!=null)sha(changed)else zip.getInputStream(zip.getEntry(part.path)).use{sha(it)}
                    require(result.getInputStream(result.getEntry(part.path)).use{sha(it)}==expected){"Word package readback mismatch"}
                }
            }
            return out
        }
    }
}
