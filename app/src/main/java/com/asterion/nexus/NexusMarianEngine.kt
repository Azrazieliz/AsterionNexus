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
    private val eos:Long,private val pad:Long,private val unknown:Long,
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
        val tokens=input+eos
        OnnxTensor.createTensor(env,arrayOf(tokens)).use{sourceIds->
            OnnxTensor.createTensor(env,arrayOf(LongArray(tokens.size){1L})).use{mask->
                encoder.run(mapOf("input_ids" to sourceIds,"attention_mask" to mask),setOf("last_hidden_state")).use{encoded->
                    val hidden=encoded[0] as OnnxTensor
                    val output=ArrayList<Long>()
                    output.add(pad)
                    val maximum=(tokens.size*4+32).coerceIn(48,384)
                    for(step in 0 until maximum){
                        if(stop()||Thread.currentThread().isInterrupted)throw InterruptedException("Translation paused")
                        OnnxTensor.createTensor(env,arrayOf(output.toLongArray())).use{prefix->
                            decoder.run(mapOf("input_ids" to prefix,"encoder_hidden_states" to hidden,
                                "encoder_attention_mask" to mask),setOf("logits")).use{decoded->
                                val logits=(decoded[0] as OnnxTensor).floatBuffer
                                val dimensions=(decoded[0].info as ai.onnxruntime.TensorInfo).shape
                                val vocabSize=dimensions.last().toInt()
                                require(vocabSize>0&&vocabSize<=500000&&logits.limit()>=vocabSize)
                                val start=logits.limit()-vocabSize
                                var best=-1L;var score=Float.NEGATIVE_INFINITY
                                for(i in 0 until vocabSize){
                                    if(i.toLong()==pad||i.toLong()==unknown)continue
                                    val value=logits.get(start+i)
                                    if(value>score){best=i.toLong();score=value}
                                }
                                check(best>=0&&score.isFinite()){"Model produced no finite target scores"}
                                output.add(best)
                            }
                        }
                        if(output.last()==eos){
                            val pieces=output.drop(1).dropLast(1).map{reverse[it]?:error("Unknown output token")}
                            check(pieces.isNotEmpty()){"Model produced no translation"}
                            val translated=String(NexusSentencePiece.decode(target,pieces.map{it.toByteArray(Charsets.UTF_8)}.toTypedArray()),Charsets.UTF_8).trim()
                            check(translated.isNotBlank()&&translated!=text.trim()){"Empty or unchanged translation"}
                            return translated
                        }
                    }
                    error("Model did not complete within bounded generation budget")
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
            "ko" to "d9fa1ac6008242100fecb4fff9b0a5917a1be81f","zh" to "39d480d52a9ea3065a1f117adfe4dbc55de10e6f")
        private fun digest(file:File):String{
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{stream->val b=ByteArray(131072);while(true){val n=stream.read(b);if(n<0)break;md.update(b,0,n)}}
            return md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        fun open(context:Context,language:String):NexusMarianEngine{
            val revision=revisions[language]?:error("Unsupported source language")
            val base="translation-models/"+language
            val manifest=context.assets.open(base+"/manifest.json").bufferedReader(Charsets.UTF_8).use{JSONObject(it.readText())}
            check(manifest.getString("revision")==revision&&manifest.getString("language")==language&&manifest.getBoolean("reference_smoke_passed")){"Unvalidated model bundle"}
            val hashes=manifest.getJSONObject("sha256")
            val directory=File(context.noBackupFilesDir,"translation-models/"+revision).apply{check(isDirectory||mkdirs()||isDirectory)}
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
            val eos=config.getLong("eos_token_id");val pad=config.getLong("decoder_start_token_id")
            val unknown=vocabulary["<unk>"]?:error("Missing unknown token")
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
                return NexusMarianEngine(source,target,encoder!!,decoder!!,vocabulary,reverse,eos,pad,unknown,"marian-"+language+"-"+revision)
            }catch(t:Throwable){
                runCatching{encoder?.close()};runCatching{decoder?.close()}
                if(source!=0L)NexusSentencePiece.close(source)
                if(target!=0L)NexusSentencePiece.close(target)
                throw t
            }
        }
    }
}
