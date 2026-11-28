package com.asterion.nexus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/** Uses the existing Asterion v1 Binder contract. No generic remote command,
 * arbitrary intent, credential export or separate Control transport. */
object NexusPartnerBridge {
    private const val PREF="nexus_partner_consent_v1"
    private const val MAX_REPLY=8192
    private const val MAX_LEASE_MS=10*60*1000L
    private val pool=ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(4),ThreadPoolExecutor.AbortPolicy())
    data class Partner(val pkg:String,val label:String,val signer:String,val approved:Boolean)
    data class Action(val name:String,val intent:String,val media:List<String>,val maxBytes:Long,val assetContract:Boolean)
    data class Operation(val id:String,val pkg:String,val signer:String,val action:String,val sha:String,
                         val mime:String,val expires:Long,val status:String,val message:String)
    private fun prefs(c:Context)=c.getSharedPreferences(PREF,0)
    private fun authority(pkg:String)=pkg+".asterion.bridge"
    private fun signer(c:Context,pkg:String):String{
        val info=c.packageManager.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures=info.signingInfo?.apkContentsSigners?:error("Signing certificate unavailable")
        check(signatures.size==1){"Unexpected signing certificate count"}
        return MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
            .joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun requireProvider(c:Context,pkg:String):String{
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        check(pkg!=c.packageName){"Cannot call own bridge"}
        val info=c.packageManager.resolveContentProvider(authority(pkg),0)?:error("Partner provider unavailable")
        check(info.packageName==pkg&&info.exported){"Unexpected provider identity"}
        return signer(c,pkg)
    }
    fun discover(c:Context):List<Partner>{
        val launch=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return c.packageManager.queryIntentActivities(launch,0).mapNotNull{entry->
            val pkg=entry.activityInfo.packageName
            if(pkg==c.packageName)return@mapNotNull null
            val sig=runCatching{requireProvider(c,pkg)}.getOrNull()?:return@mapNotNull null
            Partner(pkg,entry.loadLabel(c.packageManager).toString().take(100),sig,prefs(c).getString("allow:"+pkg,"")==sig)
        }.distinctBy{it.pkg}.sortedBy{it.label}.take(100)
    }
    /** Called only by foreground local confirmation UI. Not a bridge action. */
    @Synchronized fun approve(c:Context,p:Partner,allowed:Boolean){
        val sig=requireProvider(c,p.pkg)
        check(sig==p.signer){"Partner signer changed"}
        check(prefs(c).edit().putString("allow:"+p.pkg,if(allowed)sig else "").commit()){"Approval not saved"}
        if(!allowed){
            history(c,200).filter{it.pkg==p.pkg&&it.status in setOf("SUBMITTED","RUNNING","UNKNOWN","ACCEPTED")}.forEach{
                revoke(c,it)
                persist(c,it.copy(status="REVOKED",message="Local approval revoked"))
            }
        }
    }
    private fun approved(c:Context,pkg:String,expected:String){
        check(expected==requireProvider(c,pkg)&&prefs(c).getString("allow:"+pkg,"")==expected){"Local approval unavailable or signer changed"}
    }
    private fun call(c:Context,pkg:String,sig:String,method:String,args:Bundle,seconds:Long):JSONObject{
        approved(c,pkg,sig)
        val future=pool.submit<Bundle?>{
            approved(c,pkg,sig)
            val reply=c.contentResolver.call(Uri.parse("content://"+authority(pkg)),method,null,args)
            approved(c,pkg,sig);reply
        }
        try{
            val bundle=future.get(seconds,TimeUnit.SECONDS)?:error("Partner returned no reply")
            val raw=bundle.getString("result_json","")
            check(raw.isNotEmpty()&&raw.length<=MAX_REPLY){"Invalid partner response"}
            return JSONObject(raw)
        }catch(e:Exception){future.cancel(true);pool.purge();throw e}
    }
    fun capabilities(c:Context,p:Partner):List<Action>{
        val pack=call(c,p.pkg,p.signer,"asterion.v1.capabilities",Bundle().apply{putInt("protocol_version",1)},4)
        require(pack.optInt("pack_version",0) in 1..1000){"Unsupported capability pack"}
        val actions=pack.optJSONArray("actions")?:error("Missing partner actions")
        require(actions.length()<=80)
        return buildList{
            for(i in 0 until actions.length()){
                val row=actions.optJSONObject(i)?:continue
                val name=row.optString("name");val intent=row.optString("intent")
                if(!name.matches(Regex("[a-z][a-z0-9_.-]{0,79}"))||!intent.matches(Regex("[a-z][a-z0-9_.-]{0,79}")))continue
                val types=row.optJSONArray("media_types")
                val media=if(types==null||types.length()>40)emptyList() else (0 until types.length()).map{types.optString(it)}
                    .filter{it.matches(Regex("[a-z0-9.+-]+/([a-z0-9.+-]+|\\*)"))}
                val limit=row.optLong("max_bytes",0).coerceIn(0,128L*1024*1024*1024)
                val contract=row.optString("contract_version")=="asterion.asset-action.v1"&&
                    row.optBoolean("read_only_uri_grant")&&intent in setOf("asset.import","asset.reference")&&media.isNotEmpty()&&limit>0
                add(Action(name,intent,media,limit,contract))
            }
        }.distinctBy{it.name}
    }
    fun supports(action:Action,item:NexusRegistry.Item):Boolean=action.assetContract&&item.length<=action.maxBytes&&
        action.media.any{it==item.mime||(it.endsWith("/*")&&item.mime.startsWith(it.substringBefore('/')+"/"))}
    private fun directory(c:Context)=File(c.filesDir,"nexus-partner-receipts").apply{
        check(isDirectory||mkdirs()||isDirectory)
    }
    private fun receipt(c:Context,id:String):AtomicFile{
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return AtomicFile(File(directory(c),id+".json"))
    }
    private fun persist(c:Context,op:Operation):Operation{
        val j=JSONObject().put("version",1).put("id",op.id).put("package",op.pkg).put("signer",op.signer)
            .put("action",op.action).put("sha",op.sha).put("mime",op.mime).put("expires",op.expires)
            .put("status",op.status).put("message",op.message.take(200))
        val f=receipt(c,op.id);val out=f.startWrite()
        try{out.write(j.toString().toByteArray(Charsets.UTF_8));f.finishWrite(out)}
        catch(e:Exception){f.failWrite(out);throw e}
        return op
    }
    private fun load(c:Context,id:String):Operation{
        val bytes=receipt(c,id).readFully()
        require(bytes.size<=8192)
        val j=JSONObject(String(bytes,Charsets.UTF_8))
        check(j.getInt("version")==1&&j.getString("id")==id)
        return Operation(id,j.getString("package"),j.getString("signer"),j.getString("action"),j.getString("sha"),
            j.getString("mime"),j.getLong("expires"),j.getString("status"),j.optString("message"))
    }
    fun history(c:Context,limit:Int=80):List<Operation>{
        require(limit in 1..200)
        return directory(c).listFiles().orEmpty().filter{it.name.matches(Regex("[0-9a-f-]{36}\\.json"))}
            .sortedByDescending{it.lastModified()}.take(limit).mapNotNull{runCatching{load(c,it.nameWithoutExtension)}.getOrNull()}
    }
    fun grantUri(c:Context,id:String):Uri=Uri.parse("content://"+c.packageName+".library/grant/"+id)
    /** Provider validates the lease on every new query/open, even if Android
     * still holds a stale URI grant after an app crash or reboot. */
    @JvmStatic fun grantedSha(c:Context,id:String,uid:Int):String{
        val op=load(c,id)
        check(System.currentTimeMillis()<op.expires&&op.status in setOf("SUBMITTED","RUNNING","ACCEPTED","UNKNOWN")){"Asset grant expired or closed"}
        approved(c,op.pkg,op.signer)
        val packages=c.packageManager.getPackagesForUid(uid).orEmpty()
        check(packages.size==1&&packages.single()==op.pkg){"Asset grant caller mismatch"}
        return op.sha
    }
    private fun revoke(c:Context,op:Operation){
        c.revokeUriPermission(op.pkg,grantUri(c,op.id),Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun finish(c:Context,op:Operation,status:String,message:String):Operation{
        val next=persist(c,op.copy(status=status,message=message))
        if(status !in setOf("SUBMITTED","RUNNING","UNKNOWN","ACCEPTED"))revoke(c,next)
        return next
    }
    private fun result(c:Context,op:Operation,reply:JSONObject):Operation{
        val status=reply.optString("status","")
        if(status in setOf("accepted","running","cancel_requested")){
            return finish(c,op,if(status=="accepted")"ACCEPTED" else "RUNNING","Partner acknowledged request")
        }
        if(status in setOf("failed","rejected","unsupported","cancelled","permission_denied")){
            return finish(c,op,"FAILED","Partner reported "+status)
        }
        val payload=reply.optJSONObject("result")?:reply
        if(payload.optString("contract_version")!="asterion.asset-result.v1"||
            payload.optString("request_id")!=op.id||payload.optString("trace_id")!=op.id||
            payload.optString("source_application")!=op.pkg)
            return finish(c,op,"UNKNOWN","No matching typed asset receipt; do not replay")
        val state=payload.optString("state")
        if(state in setOf("RECEIVED","RUNNING"))return finish(c,op,"RUNNING","Typed partner receipt: "+state)
        if(state in setOf("FAILED","UNSUPPORTED","PERMISSION_DENIED","PARTIAL"))
            return finish(c,op,"FAILED","Typed partner receipt: "+state)
        if(state!="VERIFIED")return finish(c,op,"UNKNOWN","Partner outcome unresolved")
        val refs=payload.optJSONArray("output_asset_refs")
        val receipts=payload.optJSONArray("output_receipt_ids")
        if(refs==null||refs.length()!=1||receipts==null||receipts.length()==0)
            return finish(c,op,"REPORTED","Partner reported verification; no independently readable output")
        val ref=refs.getJSONObject(0)
        val sha=ref.optString("content_sha256")
        val location=ref.optString("location_ref")
        val output=runCatching{Uri.parse(location)}.getOrNull()
        if(!sha.matches(Regex("[a-f0-9]{64}"))||output==null||output.scheme!="content"||
            output.authority.isNullOrBlank()||c.packageManager.resolveContentProvider(output.authority!!,0)?.packageName!=op.pkg)
            return finish(c,op,"REPORTED","Partner receipt lacks a scoped readable output")
        // A separate real output must be granted by the specialist; never
        // access arbitrary private paths or infer target-side engine success.
        val registry=NexusRegistry(c)
        val length=c.contentResolver.openFileDescriptor(output,"r")?.use{it.statSize}?:-1L
        if(length<=0||length>128L*1024*1024*1024)return finish(c,op,"REPORTED","Partner output length unavailable")
        val mime=c.contentResolver.getType(output)?:return finish(c,op,"REPORTED","Partner output MIME unavailable")
        val saved=c.contentResolver.openInputStream(output)?.use{input->
            registry.ingest(input,"Partner output "+op.id,mime,"nexus-partner",op.pkg,op.id,length,sha)
        }?:return finish(c,op,"REPORTED","Partner output read grant unavailable")
        check(registry.verifyOriginal(saved)){"Partner output readback failed"}
        registry.linkDerivative(saved.sha256,op.sha,"derived_from",op.id)
        return finish(c,op,"VERIFIED","Independent output SHA-256 verified")
    }
    /** Only an explicitly advertised asset contract and a selected verified
     * local original can be handed to the target. No generic action runner. */
    @Synchronized fun send(c:Context,partner:Partner,selected:Action,item:NexusRegistry.Item):Operation{
        approved(c,partner.pkg,partner.signer)
        val action=capabilities(c,partner).singleOrNull{it.name==selected.name&&supports(it,item)}
            ?:error("Compatible asset capability no longer advertised")
        check(NexusRegistry(c).verifyOriginal(item)){"Selected original failed verification"}
        val id=UUID.randomUUID().toString()
        val expires=System.currentTimeMillis()+MAX_LEASE_MS
        val op=persist(c,Operation(id,partner.pkg,partner.signer,action.name,item.sha256,item.mime,expires,"SUBMITTED",""))
        val uri=grantUri(c,id)
        try{
            c.grantUriPermission(partner.pkg,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val payload=JSONObject().put("contract_version","asterion.asset-action.v1")
                .put("request_id",id).put("caller",c.packageName+":"+signer(c,c.packageName))
                .put("target_capability",action.name).put("owner_approval_scope",id)
                .put("expires_at",Instant.ofEpochMilli(expires).toString()).put("idempotency_key",id)
                .put("asset_refs",JSONArray().put(JSONObject().put("nexus_id",item.sha256)
                    .put("content_sha256",item.sha256).put("source_kind","NEXUS")
                    .put("location_ref",uri.toString()).put("media_kind",item.mime)))
                .put("parameters",JSONObject().put("byte_length",item.length)).put("trace_id",id)
            check(payload.toString().length<=4096)
            val args=Bundle().apply{
                putInt("protocol_version",1);putString("request_id",id);putString("action",action.name)
                putString("payload_json",payload.toString())
            }
            return result(c,op,call(c,partner.pkg,partner.signer,"asterion.v1.invoke",args,7))
        }catch(e:Exception){
            return finish(c,op,"UNKNOWN","Dispatch or result uncertain; no automatic replay")
        }
    }
    @Synchronized fun poll(c:Context,id:String,cancel:Boolean=false):Operation{
        val op=load(c,id)
        if(op.status !in setOf("SUBMITTED","ACCEPTED","RUNNING","UNKNOWN"))return op
        if(System.currentTimeMillis()>=op.expires){
            revoke(c,op)
            // Closing the read lease does not prove a remote operation stopped.
        }
        val args=Bundle().apply{putInt("protocol_version",1);putString("request_id",op.id)}
        return try{
            result(c,op,call(c,op.pkg,op.signer,if(cancel)"asterion.v1.cancel" else "asterion.v1.status",args,6))
        }catch(e:Exception){finish(c,op,"UNKNOWN","Partner did not confirm operation state")}
    }
    @Synchronized fun expire(c:Context){
        for(op in history(c,200))if(System.currentTimeMillis()>=op.expires)runCatching{revoke(c,op)}
    }
}
