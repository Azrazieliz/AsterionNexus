package com.azrael.galleryflow

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

/** Import the actual GalleryFlow export format, retaining historical states
 * without trusting foreign content URIs or replaying old transfers.
 * Only missing keys are inserted; all original export rows remain in a private
 * migration journal. Existing owner choices and files are never overwritten. */
class LegacyCatalogMigration(private val context:Context) {
    data class Staged(val file:File,val digest:String,val bytes:Long)
    data class Report(val digest:String,val counts:Map<String,Long>,val inserted:Long,
                      val existing:Long,val held:Long,val archived:Long,val alreadyApplied:Boolean){
        fun description():String=buildString{
            append(if(alreadyApplied)"Already applied. No duplicate writes." else "Catalog records: "+counts.values.sum())
            counts.forEach{(key,value)->append("\n").append(key).append(": ").append(value)}
            append("\nNew catalog rows: ").append(inserted)
            append("\nExisting keys retained: ").append(existing)
            append("\nTransfers held for verification: ").append(held)
            append("\nHistory-only rows: ").append(archived)
            append("\nNo original files moved or deleted. New subscriptions remain disabled until enabled explicitly.")
        }
    }
    private data class Table(val keys:List<String>,val strings:List<String>,val numbers:List<String>)
    private val tables=linkedMapOf(
        "entities" to Table(listOf("source","entity_id"),listOf("display_name","canonical_url","kind","live_cursor","last_error"),listOf("selected","live_enabled","archived","backfill_page","last_sync_at")),
        "galleries" to Table(listOf("source","gallery_id"),listOf("entity_id","canonical_url","title","tags_json","published_at","state","last_error"),listOf("retrieved_at","media_count")),
        "media" to Table(listOf("source","media_id"),listOf("gallery_id","entity_id","url","normalized_url","referer","content_uri","filename","mime_type","sha256","ahash64","duplicate_of","state"),listOf("media_index","bytes","updated_at")),
        "identity_aliases" to Table(listOf("source","source_entity_id"),listOf("canonical_identity","confidence"),emptyList()),
        "events" to Table(listOf("id"),listOf("event_type","payload_json"),listOf("created_at")),
        "backfill_retries" to Table(listOf("source","gallery_id"),listOf("entity_id","canonical_url","title","last_error"),listOf("retry_after","attempts"))
    )
    private val root=File(context.noBackupFilesDir,"legacy-catalog-imports").apply{check(isDirectory||mkdirs()||isDirectory)}
    fun stage(uri:Uri):Staged{
        require(uri.scheme=="content"){"Select a document through Android storage access"}
        val temp=File.createTempFile("catalog-", ".part",root)
        val digest=MessageDigest.getInstance("SHA-256")
        var count=0L
        try{
            context.contentResolver.openInputStream(uri)?.use{input->
                temp.outputStream().use{output->
                    val buf=ByteArray(131072)
                    while(true){
                        val n=input.read(buf);if(n<0)break
                        count+=n;require(count<=1024L*1024*1024){"Catalog exceeds 1 GiB import limit"}
                        digest.update(buf,0,n);output.write(buf,0,n)
                    }
                    output.fd.sync()
                }
            }?:error("Selected export cannot be read")
            val sha=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            val target=File(root,sha+".json")
            if(target.exists()){
                check(hash(target)==sha){"Private staging digest mismatch"}
            }else check(temp.renameTo(target)){"Cannot publish verified export snapshot"}
            return Staged(target,sha,count)
        }finally{temp.delete()}
    }
    private fun hash(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buf=ByteArray(131072);while(true){val n=input.read(buf);if(n<0)break;digest.update(buf,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun walk(staged:Staged,visit:(String,Table,JSONObject)->Unit){
        require(staged.file.parentFile?.canonicalFile==root.canonicalFile&&hash(staged.file)==staged.digest){"Export snapshot changed"}
        var seen=0L
        var entities=false
        val names=mutableSetOf<String>()
        JsonReader(InputStreamReader(staged.file.inputStream(),Charsets.UTF_8)).use{r->
            r.beginObject()
            while(r.hasNext()){
                val name=r.nextName()
                val table=tables[name]
                if(table==null){r.skipValue();continue}
                require(names.add(name)){"Duplicate catalog table"}
                if(name=="entities")entities=true
                r.beginArray()
                while(r.hasNext()){
                    require(++seen<=2_000_000){"Catalog row limit exceeded"}
                    val row=JSONObject()
                    r.beginObject()
                    while(r.hasNext()){
                        val column=r.nextName()
                        require(!row.has(column)){"Duplicate catalog column"}
                        val value:Any=when(r.peek()){
                            JsonToken.STRING->r.nextString().also{require(it.length<=2_000_000){"Oversized catalog field"}}
                            JsonToken.NUMBER->r.nextString().let{it.toLongOrNull()?:it.toDouble()}
                            JsonToken.BOOLEAN->r.nextBoolean()
                            JsonToken.NULL->{r.nextNull();JSONObject.NULL}
                            else->error("Unsupported nested catalog field")
                        }
                        row.put(column,value)
                    }
                    r.endObject();visit(name,table,row)
                }
                r.endArray()
            }
            r.endObject();require(r.peek()==JsonToken.END_DOCUMENT){"Unexpected trailing export content"}
        }
        require(entities){"Not a GalleryFlow catalog export"}
    }
    private fun key(table:Table,row:JSONObject):Array<String> = table.keys.map{
        require(row.has(it)&&!row.isNull(it)){"Catalog identity missing"}
        row.getString(it).also{v->require(v.isNotBlank()&&v.length<=4096){"Invalid catalog identity"}}
    }.toTypedArray()
    private fun where(table:Table)=table.keys.joinToString(" AND "){it+"=?"}
    private fun snapshot(db:SQLiteDatabase,name:String,table:Table,keys:Array<String>):String?=
        db.query(name,null,where(table),keys,null,null,null,"1").use{c->
            if(!c.moveToFirst())null else JSONObject().apply{
                c.columnNames.sorted().forEach{column->
                    val i=c.getColumnIndexOrThrow(column)
                    put(column,when(c.getType(i)){
                        Cursor.FIELD_TYPE_NULL->JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
                        else->c.getString(i)
                    })
                }
            }.toString()
        }
    private fun values(name:String,table:Table,row:JSONObject,keys:Array<String>):ContentValues?{
        if(name=="events"||name=="backfill_retries")return null
        val source=SourceId.entries.firstOrNull{it.wireName==row.optString("source")}?:return null
        val v=ContentValues()
        table.keys.forEachIndexed{i,k->v.put(k,keys[i])}
        table.strings.forEach{k->if(row.has(k)){if(row.isNull(k))v.putNull(k)else v.put(k,row.getString(k))}}
        table.numbers.forEach{k->if(row.has(k)&&!row.isNull(k)){
            val n=when(val value=row.get(k)){is Boolean->if(value)1L else 0L;else->value.toString().toLongOrNull()?:error("Invalid catalog number")}
            v.put(k,n)
        }}
        fun required(vararg fields:String)=fields.forEach{require(!v.getAsString(it).isNullOrBlank()){"Missing catalog field "+it}}
        when(name){
            "entities"->{
                required("display_name","canonical_url","kind")
                val url=v.getAsString("canonical_url")
                val valid=runCatching{val u=java.net.URI(url);u.scheme in setOf("https","http")&&!u.host.isNullOrBlank()&&u.userInfo==null}.getOrDefault(false)
                if(!valid)return null
                v.put("selected",0);v.put("live_enabled",0)
                val retired=source==SourceId.FOUR_K_GIRL||com.azrael.pixivdumpsync.ProviderRetirement.isRetired(url)
                v.put("archived",if(retired||row.optInt("archived",0)!=0)1 else 0)
                v.put("backfill_page",row.optInt("backfill_page",0).coerceIn(0,500000))
            }
            "galleries"->{required("entity_id","canonical_url","title");v.put("state","cancelled");v.put("last_error","Imported history; original availability must be verified")}
            "media"->{
                required("gallery_id","entity_id","url")
                v.putNull("content_uri");v.put("state","cancelled")
                if(!v.containsKey("media_index"))v.put("media_index",0)
                if(!v.containsKey("updated_at"))v.put("updated_at",0L)
            }
            "identity_aliases"->{required("canonical_identity");if(!v.containsKey("confidence"))v.put("confidence","explicit")}
        }
        return v
    }
    fun receipts():List<Pair<String,String>> = GalleryDb(context).use{helper->
        helper.readableDatabase.rawQuery("SELECT digest,receipt_json FROM legacy_catalog_migrations ORDER BY applied_at DESC LIMIT 100",null).use{c->
            buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}
        }
    }

    fun preview(staged:Staged):Report=process(staged,false)
    fun apply(staged:Staged):Report=process(staged,true)
    private fun process(staged:Staged,apply:Boolean):Report{
        GalleryDb(context).use{helper->
            val db=helper.writableDatabase
            db.beginTransactionNonExclusive()
            try{
                if(db.rawQuery("SELECT 1 FROM legacy_catalog_migrations WHERE digest=?",arrayOf(staged.digest)).use{it.moveToFirst()})
                    return Report(staged.digest,emptyMap(),0,0,0,0,true)
                val counts=linkedMapOf<String,Long>()
                var inserted=0L;var existing=0L;var held=0L;var archived=0L
                walk(staged){name,table,row->
                    counts[name]=(counts[name]?:0)+1
                    val keys=key(table,row)
                    val rowKey=JSONArray(keys.toList()).toString()
                    val value=values(name,table,row,keys)
                    var applied:String?=null
                    if(value==null)archived++ else if(snapshot(db,name,table,keys)!=null)existing++ else {
                        inserted++
                        if(name=="media"||name=="galleries")held++
                        // Preview executes the same constrained inserts in a
                        // transaction that is rolled back, so its counts match.
                        db.insertOrThrow(name,null,value)
                        applied=snapshot(db,name,table,keys)?:error("Catalog insert was not visible")
                    }
                    db.insertOrThrow("legacy_catalog_rows",null,ContentValues().apply{
                        put("digest",staged.digest);put("table_name",name);put("row_key",rowKey)
                        put("payload_json",row.toString());put("applied_json",applied)
                    })
                }
                // Check references only for rows actually created by this import.
                // Reject the whole transaction if an imported parent is missing.
                for((name,sql) in listOf(
                    "galleries" to "SELECT 1 FROM entities WHERE source=? AND entity_id=?",
                    "media" to "SELECT 1 FROM galleries WHERE source=? AND gallery_id=? AND entity_id=?",
                    "identity_aliases" to "SELECT 1 FROM entities WHERE source=? AND entity_id=?"
                )){
                    db.rawQuery("SELECT applied_json FROM legacy_catalog_rows WHERE digest=? AND table_name=? AND applied_json IS NOT NULL",arrayOf(staged.digest,name)).use{c->
                        while(c.moveToNext()){
                            val row=JSONObject(c.getString(0))
                            val args=when(name){
                                "media"->arrayOf(row.getString("source"),row.getString("gallery_id"),row.getString("entity_id"))
                                "identity_aliases"->arrayOf(row.getString("source"),row.getString("source_entity_id"))
                                else->arrayOf(row.getString("source"),row.getString("entity_id"))
                            }
                            check(db.rawQuery(sql,args).use{it.moveToFirst()}){"Missing imported catalog relationship"}
                        }
                    }
                }
                val report=Report(staged.digest,counts,inserted,existing,held,archived,false)
                if(apply){
                    db.insertOrThrow("legacy_catalog_migrations",null,ContentValues().apply{
                        put("digest",staged.digest);put("applied_at",System.currentTimeMillis())
                        put("receipt_json",JSONObject().put("counts",JSONObject(counts)).put("inserted",inserted)
                            .put("existing",existing).put("held",held).put("archived",archived).toString())
                    })
                    db.setTransactionSuccessful()
                }
                return report
            }finally{db.endTransaction()}
        }
    }
    /** Explicit rollback only if every inserted row is still exactly the
     * post-import snapshot. Any later edit prevents all rollback writes. */
    fun rollback(digest:String):Long{
        require(digest.matches(Regex("[a-f0-9]{64}")))
        GalleryDb(context).use{helper->
            val db=helper.writableDatabase
            db.beginTransactionNonExclusive()
            try{
                var removed=0L
                for((name,table) in tables.entries.reversed()){
                    if(name=="events"||name=="backfill_retries")continue
                    db.rawQuery("SELECT row_key,applied_json FROM legacy_catalog_rows WHERE digest=? AND table_name=? AND applied_json IS NOT NULL",arrayOf(digest,name)).use{c->
                        while(c.moveToNext()){
                            val key=JSONArray(c.getString(0))
                            val keys=Array(key.length()){key.getString(it)}
                            check(snapshot(db,name,table,keys)==c.getString(1)){"Imported catalog has changed; rollback refused"}
                            if(name=="entities"){
                                check(!db.rawQuery("SELECT 1 FROM galleries WHERE source=? AND entity_id=? LIMIT 1",keys).use{it.moveToFirst()}){"Later gallery references prevent rollback"}
                                check(!db.rawQuery("SELECT 1 FROM media WHERE source=? AND entity_id=? LIMIT 1",keys).use{it.moveToFirst()}){"Later media references prevent rollback"}
                                check(!db.rawQuery("SELECT 1 FROM identity_aliases WHERE source=? AND source_entity_id=? LIMIT 1",keys).use{it.moveToFirst()}){"Later identity references prevent rollback"}
                                check(!db.rawQuery("SELECT 1 FROM backfill_retries WHERE source=? AND entity_id=? LIMIT 1",keys).use{it.moveToFirst()}){"Later retry references prevent rollback"}
                            }
                            if(name=="galleries")check(!db.rawQuery("SELECT 1 FROM media WHERE source=? AND gallery_id=? LIMIT 1",keys).use{it.moveToFirst()}){"Later media references prevent rollback"}
                            check(db.delete(name,where(table),keys)==1)
                            removed++
                        }
                    }
                }
                db.delete("legacy_catalog_rows","digest=?",arrayOf(digest))
                db.delete("legacy_catalog_migrations","digest=?",arrayOf(digest))
                db.setTransactionSuccessful()
                return removed
            }finally{db.endTransaction()}
        }
    }
}
