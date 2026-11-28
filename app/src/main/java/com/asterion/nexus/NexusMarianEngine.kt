package com.asterion.nexus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object NexusSentencePiece{
    init{System.loadLibrary("nexus_sentencepiece")}
    external fun open(path:ByteArray):Long
    external fun encode(handle:Long,input:ByteArray):Array<ByteArray>
    external fun decode(handle:Long,input:Array<ByteArray>):ByteArray
    external fun close(handle:Long)
}
internal class NexusMarianEngine private constructor(
    private val source:Long,private val target:Long,
    private val encoder:OrtSession,private val decoder:OrtSession,
    private val vocabulary:Map<String,Long>,private val reverse:Map<Long,String>,
    private val eos:Long,private val unknown:Long,
    private val sourcePrefix:LongArray,private val targetPrefix:List<Long>,private val suppressed:Set<Long>,
    val identity:String
):Closeable{
    private val env=OrtEnvironment.getEnvironment()
    private fun ids(text:String):LongArray=NexusSentencePiece.encode(source,text.toByteArray(Charsets.UTF_8))
        .map{vocabulary[String(it,Charsets.UTF_8)]?:unknown}.toLongArray()
    fun translate(text:String,stop:()->Boolean):String{
        if(text.isBlank())return text
        val input=ids(text)
        require(input.isNotEmpty()&&input.size<=224){"Translation segment exceeds model input budget"}
        require(input.none{it==unknown}){"Translation would discard unknown source tokens"}
        val tokens=sourcePrefix+input+eos
        OnnxTensor.createTensor(env,arrayOf(tokens)).use{sourceIds->
            OnnxTensor.createTensor(env,arrayOf(LongArray(tokens.size){1L})).use{mask->
                encoder.run(mapOf("input_ids" to sourceIds,"attention_mask" to mask),setOf("last_hidden_state")).use{encoded->
                    val hidden=encoded[0] as OnnxTensor
                    data class Beam(val ids:List<Long>,val total:Double){
                        val rank:Double get()=total/(ids.size-targetPrefix.size).coerceAtLeast(1)
                    }
                    val width=4
                    var beams=listOf(Beam(targetPrefix,0.0))
                    var completed=emptyList<Beam>()
                    val maximum=(tokens.size*4+32).coerceIn(48,384)
                    for(step in 0 until maximum){
                        val candidates=ArrayList<Beam>()
                        val finished=ArrayList<Beam>(completed)
                        for(beam in beams){
                            if(stop()||Thread.currentThread().isInterrupted)throw InterruptedException("Translation paused")
                            OnnxTensor.createTensor(env,arrayOf(beam.ids.toLongArray())).use{prefix->
                                decoder.run(mapOf("input_ids" to prefix,"encoder_hidden_states" to hidden,
                                    "encoder_attention_mask" to mask),setOf("logits")).use{decoded->
                                    val logits=(decoded[0] as OnnxTensor).floatBuffer
                                    val dimensions=(decoded[0].info as ai.onnxruntime.TensorInfo).shape
                                    val vocabSize=dimensions.last().toInt()
                                    require(vocabSize>0&&vocabSize<=500000&&logits.limit()>=vocabSize)
                                    val start=logits.limit()-vocabSize
                                    var peak=Double.NEGATIVE_INFINITY
                                    for(i in 0 until vocabSize)peak=maxOf(peak,logits.get(start+i).toDouble())
                                    check(peak.isFinite()){"Model produced no finite target scores"}
                                    var sum=0.0
                                    val top=ArrayList<Pair<Long,Double>>()
                                    for(i in 0 until vocabSize){
                                        val value=logits.get(start+i).toDouble()
                                        sum+=kotlin.math.exp(value-peak)
                                        val id=i.toLong()
                                        if(!value.isFinite()||id in suppressed||(beam.ids.size==targetPrefix.size&&id==eos))continue
                                        if(top.size<width||value>top.last().second){
                                            top.add(id to value)
                                            top.sortWith(compareByDescending<Pair<Long,Double>>{it.second}.thenBy{it.first})
                                            if(top.size>width)top.removeAt(top.lastIndex)
                                        }
                                    }
                                    val normalizer=peak+kotlin.math.ln(sum)
                                    check(normalizer.isFinite())
                                    for((id,value) in top){
                                        val candidate=Beam(beam.ids+id,beam.total+value-normalizer)
                                        if(id==eos)finished.add(candidate)else candidates.add(candidate)
                                    }
                                }
                            }
                        }
                        completed=finished.sortedByDescending{it.rank}.take(width)
                        beams=candidates.sortedByDescending{it.total}.take(width)
                        if(beams.isEmpty()||(completed.size>=width&&beams.maxOf{it.rank}<=completed.last().rank))break
                    }
                    val output=completed.firstOrNull()?.ids?:error("Model did not complete within bounded generation budget")
                    val pieces=output.drop(targetPrefix.size).dropLast(1).map{reverse[it]?:error("Unknown output token")}
                    check(pieces.isNotEmpty()){"Model produced no translation"}
                    val translated=String(NexusSentencePiece.decode(target,pieces.map{it.toByteArray(Charsets.UTF_8)}.toTypedArray()),Charsets.UTF_8).trim()
                    check(translated.isNotBlank()&&translated!=text.trim()){"Empty or unchanged translation"}
                    return translated
                }
            }
        }
    }
    override fun close(){try{encoder.close()}finally{try{decoder.close()}finally{
        NexusSentencePiece.close(source);NexusSentencePiece.close(target)
    }}}
    companion object{
        private val files=listOf("encoder.onnx","decoder.onnx","source.spm","target.spm","vocab.json","config.json")
        private val revisions=mapOf("ja" to "1a906cfaaf7c8f4193f67f5885c082aa6dbd9d16",
            "ko" to "d9fa1ac6008242100fecb4fff9b0a5917a1be81f","zh" to "6540b5e5f5d78a277bc7de2b566362a61b772103")
        private fun digest(file:File):String{
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{stream->val b=ByteArray(131072);while(true){val n=stream.read(b);if(n<0)break;md.update(b,0,n)}}
            return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        fun open(context:Context,language:String):NexusMarianEngine{
            val revision=revisions[language]?:error("Unsupported source language")
            val base="translation-models/"+language
            val multilingual=language=="zh"
            val precision=if(language=="ko")"fp32" else "int8"
            val policy=if(multilingual)"beam4-m2m100-v1" else "beam4-v1"
            val manifest=context.assets.open(base+"/manifest.json").bufferedReader(Charsets.UTF_8).use{JSONObject(it.readText())}
            check(manifest.getString("revision")==revision&&manifest.getString("language")==language&&manifest.getBoolean("reference_smoke_passed")&&manifest.optString("decode_policy")==policy&&manifest.optString("precision")==precision){"Unvalidated model bundle"}
            val hashes=manifest.getJSONObject("sha256")
            val directory=File(context.noBackupFilesDir,"translation-models/"+revision+"-"+precision).apply{check(isDirectory||mkdirs()||isDirectory)}
            for(name in files){
                val expected=hashes.getString(name)
                require(expected.matches(Regex("[a-f0-9]{64}")))
                val dest=File(directory,name)
                if(!dest.isFile||digest(dest)!=expected){
                    val temp=File.createTempFile("install-",null,directory)
                    try{
                        context.assets.open(base+"/"+name).use{input->temp.outputStream().use{output->input.copyTo(output,131072)}}
                        check(digest(temp)==expected){"Translation model digest mismatch"}
                        check(temp.renameTo(dest)){"Model install failed"}
                    }finally{temp.delete()}
                }
            }
            val config=JSONObject(File(directory,"config.json").readText(Charsets.UTF_8))
            val data=JSONObject(File(directory,"vocab.json").readText(Charsets.UTF_8))
            val vocabulary=data.keys().asSequence().associateWith{data.getLong(it)}
            val reverse=vocabulary.entries.associate{it.value to it.key}
            val eos=config.getLong("eos_token_id");val start=config.getLong("decoder_start_token_id")
            val unknown=vocabulary["<unk>"]?:error("Missing unknown token")
            fun ids(key:String):List<Long>{val a=config.optJSONArray(key)?:return emptyList();return (0 until a.length()).map{a.getLong(it)}}
            val sourcePrefix=if(multilingual)ids("nexus_source_prefix") else emptyList()
            val targetPrefix=listOf(start)+(if(multilingual)ids("nexus_target_prefix") else emptyList())
            val suppressed=if(multilingual)ids("nexus_suppressed_ids").toSet()+unknown else setOf(start,unknown)
            if(multilingual){
                check(manifest.optString("repo")=="Xenova/m2m100_418M"&&config.optString("model_type")=="m2m_100")
                check(sourcePrefix==listOf(vocabulary.getValue("__zh__"))&&targetPrefix==listOf(start,vocabulary.getValue("__en__")))
                check(eos !in suppressed&&suppressed.contains(unknown)&&suppressed.contains(config.getLong("nexus_pad")))
            }
            check((sourcePrefix+targetPrefix+suppressed+eos).all{it in reverse})
            var source=0L;var target=0L;var encoder:OrtSession?=null;var decoder:OrtSession?=null
            try{
                source=NexusSentencePiece.open(File(directory,"source.spm").absolutePath.toByteArray(StandardCharsets.UTF_8))
                target=NexusSentencePiece.open(File(directory,"target.spm").absolutePath.toByteArray(StandardCharsets.UTF_8))
                OrtSession.SessionOptions().use{options->
                    options.setIntraOpNumThreads(3);options.setInterOpNumThreads(1)
                    val env=OrtEnvironment.getEnvironment()
                    encoder=env.createSession(File(directory,"encoder.onnx").absolutePath,options)
                    decoder=env.createSession(File(directory,"decoder.onnx").absolutePath,options)
                }
                check(encoder!!.inputNames==setOf("input_ids","attention_mask"))
                check(decoder!!.inputNames==setOf("input_ids","encoder_attention_mask","encoder_hidden_states"))
                return NexusMarianEngine(source,target,encoder!!,decoder!!,vocabulary,reverse,eos,unknown,sourcePrefix.toLongArray(),targetPrefix,suppressed,(if(multilingual)"m2m100-" else "marian-")+language+"-"+revision+"-"+policy+"-"+precision)
            }catch(t:Throwable){
                runCatching{encoder?.close()};runCatching{decoder?.close()}
                if(source!=0L)NexusSentencePiece.close(source)
                if(target!=0L)NexusSentencePiece.close(target)
                throw t
            }
        }
    }
}
