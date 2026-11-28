package com.asterion.nexus

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.azrael.galleryflow.LegacyCatalogMigration
import com.azrael.pixivdumpsync.*
import com.asterion.videoflow.VideoDb
import org.json.JSONObject
import java.security.MessageDigest
import java.net.URI

/** Explicit, non-destructive donor SQLite import. Actual old rows are archived
 * separately from live transfer state. Foreign saved URIs, credentials and
 * active leases are never copied. A completed job is not proof of local bytes. */
internal class NexusDonorSqliteMigration(private val context:Context){
    private data class Table(val name:String,val keys:List<String>,val columns:List<String>)
    private data class Schema(val id:String,val tables:List<Table>)
    private fun table(n:String,k:String,c:String)=Table(n,k.split(" "),c.split(" "))
    private val schemas=listOf(
        Schema("Pixiv",listOf(
            table("artists","user_id","user_id label selected live_enabled live_cursor archive_status known_total last_sync_at"),
            table("works","illust_id","illust_id artist_id title state page_count liked_marked bookmark_marked updated_at"),
            table("pages","illust_id page_index","illust_id page_index filename saved"))),
        Schema("Social",listOf(
            table("sources","url","url handle cursor state updated live_enabled backfill_enabled history_complete"),
            table("photos","photo_url","source post_id photo_url status attempts updated"))),
        Schema("Video",listOf(
            table("watches","address","address label enabled last_check cursor backfill_complete"),
            table("videos","url","url title state progress watch_id updated attempts request_origin"))),
        Schema("Comics",listOf(
            table("tasks","url","url title state progress attempts updated sync_enabled last_check fingerprint requested_by"))),
        Schema("Source index",listOf(
            table("sources","url","url name area enabled cursor last_check discovered adapter history_complete"),
            table("entries","url","url source title preview area kind discovered_at queued attempts detail_checked detail_attempts")))
    )
    data class Report(val kind:String,val digest:String,val seen:Long,val inserted:Long,val held:Long,val skipped:Long,val committed:Boolean){
        fun description()=kind+" · "+if(committed)"Imported" else "Dry run"
        fun detail()=description()+"\nRecords: "+seen+"\nNew active-catalog records (paused where applicable): "+inserted+"\nHistorical-only / held: "+held+"\nExisting identities preserved: "+skipped+"\nSnapshot: "+digest.take(16)+"\nForeign file grants and credentials not imported."
    }
    private fun helper(id:String):SQLiteOpenHelper=when(id){
        "Pixiv"->AppDb(context)
        "Social"->XPhotoQueue(context)
        "Video"->VideoDb(context)
        "Comics"->ChapterQueue(context)
        "Source index"->SourceIndexDb(context)
        else->error("Unsupported donor database")
    }
    private fun sha(file:java.io.File):String{
        val d=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val bytes=ByteArray(131072);while(true){val n=input.read(bytes);if(n<0)break;if(n>0)d.update(bytes,0,n)}}
        return d.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun columnNames(db:SQLiteDatabase,name:String):Set<String>{
        if(!db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()})return emptySet()
        return db.rawQuery("PRAGMA table_info("+name+")",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
    }
    private fun schema(db:SQLiteDatabase):Schema{
        val matching=schemas.filter{s->s.tables.all{t->columnNames(db,t.name).containsAll(t.keys)} &&
            when(s.id){"Social"->"handle" in columnNames(db,"sources");"Source index"->"area" in columnNames(db,"sources");"Comics"->"title" in columnNames(db,"tasks")&&"url" in columnNames(db,"tasks");else->true}}
        require(matching.size==1){"Select a supported, standalone donor database snapshot"}
        return matching.single()
    }
    private fun publicLink(raw:String?):Boolean{
        if(raw.isNullOrBlank()||raw.length>4096)return false
        return runCatching{
            val u=URI(raw)
            SourceIndexParser.safe(raw)&&u.fragment==null&&!u.rawQuery.orEmpty().split('&').any{
                it.substringBefore('=').lowercase(java.util.Locale.ROOT).let{k->k in setOf("token","access_token","auth","authorization","cookie","password","secret","key","signature","sig","session","sessionid")}
            }
        }.getOrDefault(false)
    }
    private fun records(db:SQLiteDatabase,table:Table,read:(ContentValues)->Unit){
        val names=columnNames(db,table.name)
        require(names.containsAll(table.keys)){"Donor key columns missing"}
        val columns=table.columns.filter{it in names}
        db.query(table.name,columns.toTypedArray(),null,null,null,null,null).use{c->
            while(c.moveToNext()){
                val row=ContentValues()
                for(i in columns.indices){
                    val k=columns[i]
                    when(c.getType(i)){
                        Cursor.FIELD_TYPE_NULL->row.putNull(k)
                        Cursor.FIELD_TYPE_INTEGER->row.put(k,c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT->row.put(k,c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING->{val v=c.getString(i);require(v.length<=131072){"Oversized donor field"};row.put(k,v)}
                        else->error("Unexpected donor binary column")
                    }
                }
                read(row)
            }
        }
    }
    private fun json(values:ContentValues)=JSONObject().apply{
        values.keySet().sorted().forEach{put(it,values.get(it)?:JSONObject.NULL)}
    }.toString()
    private fun snapshot(db:SQLiteDatabase,t:Table,key:Array<String>):String?=
        db.query(t.name,null,t.keys.joinToString(" AND "){it+"=?"},key,null,null,null,"1").use{c->
            if(!c.moveToFirst())null else JSONObject().apply{
                c.columnNames.sorted().forEach{n->
                    val i=c.getColumnIndexOrThrow(n)
                    put(n,when(c.getType(i)){Cursor.FIELD_TYPE_NULL->JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER->c.getLong(i);Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)})
                }
            }.toString()
        }
    private fun createJournal(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS nexus_donor_imports(digest TEXT PRIMARY KEY,kind TEXT NOT NULL,report TEXT NOT NULL,applied INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nexus_donor_import_rows(seq INTEGER PRIMARY KEY AUTOINCREMENT,digest TEXT NOT NULL,table_name TEXT NOT NULL,row_key TEXT NOT NULL,history TEXT NOT NULL,inserted_snapshot TEXT)")
    }
    private fun activeRow(s:Schema,t:Table,legacy:ContentValues,source:SQLiteDatabase,target:SQLiteDatabase):ContentValues?{
        val v=ContentValues(legacy)
        fun text(k:String)=legacy.getAsString(k)
        fun link(k:String)=publicLink(text(k))
        fun remove(vararg keys:String){keys.forEach{v.remove(it)}}
        // Never replay imported jobs, previous "saved" claims or donor permissions.
        when(s.id){
            "Pixiv"->when(t.name){
                "artists"->{require(text("user_id").orEmpty().matches(Regex("[0-9]{1,20}")));v.put("selected",0);v.put("live_enabled",0);v.put("enabled",1);v.put("archive_status","NOT_STARTED");remove("live_cursor")}
                // Preserve completed and partial history, but do not cause the
                // original engine to skip an unverified work or mark account actions.
                else->return null
            }
            "Social"->when(t.name){
                "sources"->{if(!link("url")||XProfileUrlPolicy.parse(text("url").orEmpty())==null)return null
                    v.put("state","disabled");v.put("live_enabled",0);v.put("backfill_enabled",0);remove("cursor");v.put("history_complete",0)}
                else->{if(!link("source")||!link("photo_url"))return null
                    if(!target.rawQuery("SELECT 1 FROM sources WHERE url=?",arrayOf(text("source"))).use{it.moveToFirst()})return null
                    v.put("status","paused");v.put("error","Imported history; retry only after verifying original")}
            }
            "Video"->when(t.name){
                "watches"->{if(!link("address"))return null;v.put("enabled",0);remove("cursor");v.put("backfill_complete",0)}
                else->{if(!link("url"))return null
                    val watch=legacy.getAsLong("watch_id")?:0L
                    val address=if(watch>0)source.rawQuery("SELECT address FROM watches WHERE id=?",arrayOf(watch.toString())).use{if(it.moveToFirst())it.getString(0)else null}else null
                    val mapped=address?.let{a->target.rawQuery("SELECT id FROM watches WHERE address=?",arrayOf(a)).use{if(it.moveToFirst())it.getLong(0)else 0L}}?:0L
                    v.put("watch_id",mapped);v.put("state","paused");v.put("progress",0);v.put("request_origin","legacy")
                    v.put("error","Imported history; original not yet verified")}
            }
            "Comics"->{if(!link("url"))return null
                v.put("state","paused");v.put("progress",0);v.put("sync_enabled",0);v.put("requested_by","legacy")
                v.put("error","Imported history; original not yet verified");remove("fingerprint")}
            "Source index"->when(t.name){
                "sources"->{if(!link("url")||ProviderRetirement.isRetired(text("url").orEmpty()))return null;v.put("enabled",0);remove("cursor");v.put("history_complete",0)}
                else->{if(!link("url")||!link("source"))return null
                    // Already-active source configurations must never receive
                    // unsolicited historical acquisition candidates.
                    if(!target.rawQuery("SELECT 1 FROM sources WHERE url=? AND enabled=0",arrayOf(text("source"))).use{it.moveToFirst()})return null
                    if(!link("preview"))v.putNull("preview")
                    v.put("queued",0);v.put("retry_after",Long.MAX_VALUE);v.put("detail_retry_after",Long.MAX_VALUE)
                }
            }
        }
        // Persist only fields supported by this actual current donor schema.
        val allowed=columnNames(target,t.name)
        v.keySet().toList().filter{it !in allowed}.forEach{v.remove(it)}
        return v
    }
    @Synchronized fun process(staged:LegacyCatalogMigration.Staged,apply:Boolean):Report{
        require(staged.digest.matches(Regex("[a-f0-9]{64}"))&&staged.file.length()==staged.bytes&&sha(staged.file)==staged.digest){"Staged database changed"}
        SQLiteDatabase.openDatabase(staged.file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{source->
            require(source.rawQuery("PRAGMA quick_check(1)",null).use{it.moveToFirst()&&it.getString(0)=="ok"}){"Donor database integrity check failed"}
            val s=schema(source)
            helper(s.id).use{h->
                val db=h.writableDatabase
                db.beginTransactionNonExclusive()
                try{
                    createJournal(db)
                    val prior=db.rawQuery("SELECT report FROM nexus_donor_imports WHERE digest=?",arrayOf(staged.digest)).use{if(it.moveToFirst())JSONObject(it.getString(0))else null}
                    if(prior!=null)return Report(s.id,staged.digest,prior.getLong("seen"),prior.getLong("inserted"),prior.getLong("held"),prior.getLong("skipped"),true)
                    var seen=0L;var inserted=0L;var held=0L;var skipped=0L
                    for(t in s.tables)records(source,t){legacy->
                        require(++seen<=2_000_000L){"Donor migration record limit exceeded"}
                        val key=t.keys.map{k->legacy.getAsString(k)?.takeIf{it.isNotBlank()&&it.length<=4096}?:error("Invalid donor identity")}.toTypedArray()
                        // The source snapshot remains in private staging. Historical
                        // ledger fields intentionally omit URLs, cursors and secrets.
                        val history=ContentValues(legacy)
                        history.keySet().toList().filter{it in setOf("url","address","source","photo_url","preview","cursor","live_cursor")}.forEach{history.remove(it)}
                        var snapshot:String?=null
                        val active=activeRow(s,t,legacy,source,db)
                        if(active==null)held++ else if(snapshot(db,t,key)!=null)skipped++ else{
                            db.insertOrThrow(t.name,null,active)
                            snapshot=snapshot(db,t,key)?:error("Inserted record missing")
                            inserted++
                        }
                        val keyJson=org.json.JSONArray(key.toList()).toString()
                        db.insertOrThrow("nexus_donor_import_rows",null,ContentValues().apply{
                            put("digest",staged.digest);put("table_name",t.name);put("row_key",keyJson);put("history",json(history));put("inserted_snapshot",snapshot)
                        })
                    }
                    val report=JSONObject().put("seen",seen).put("inserted",inserted).put("held",held).put("skipped",skipped)
                    db.insertOrThrow("nexus_donor_imports",null,ContentValues().apply{put("digest",staged.digest);put("kind",s.id);put("report",report.toString());put("applied",System.currentTimeMillis())})
                    if(apply)db.setTransactionSuccessful()
                    return Report(s.id,staged.digest,seen,inserted,held,skipped,apply)
                }finally{db.endTransaction()}
            }
        }
    }
    data class Receipt(val kind:String,val digest:String,val description:String)
    fun receipts():List<Receipt> = schemas.flatMap{s->helper(s.id).use{h->
        val db=h.readableDatabase
        if(columnNames(db,"nexus_donor_imports").isEmpty())emptyList()
        else db.rawQuery("SELECT digest,report FROM nexus_donor_imports ORDER BY applied DESC LIMIT 20",null).use{c->
            buildList{while(c.moveToNext())add(Receipt(s.id,c.getString(0),c.getString(1)))}
        }
    }}
    @Synchronized fun rollback(kind:String,digest:String):Long{
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val s=schemas.single{it.id==kind}
        helper(kind).use{h->
            val db=h.writableDatabase
            db.beginTransactionNonExclusive()
            try{
                require(db.rawQuery("SELECT 1 FROM nexus_donor_imports WHERE digest=?",arrayOf(digest)).use{it.moveToFirst()}){"Import receipt unavailable"}
                var removed=0L
                db.rawQuery("SELECT table_name,row_key,inserted_snapshot FROM nexus_donor_import_rows WHERE digest=? AND inserted_snapshot IS NOT NULL ORDER BY seq DESC",arrayOf(digest)).use{c->
                    while(c.moveToNext()){
                        val t=s.tables.single{it.name==c.getString(0)}
                        val a=org.json.JSONArray(c.getString(1));val key=Array(a.length()){a.getString(it)}
                        check(snapshot(db,t,key)==c.getString(2)){"Imported record changed; rollback refused"}
                        val dependency=when(s.id+"."+t.name){
                            "Pixiv.artists"->"SELECT 1 FROM works WHERE artist_id=?"
                            "Social.sources"->"SELECT 1 FROM photos WHERE source=?"
                            "Source index.sources"->"SELECT 1 FROM entries WHERE source=?"
                            "Video.watches"->"SELECT 1 FROM videos WHERE watch_id IN (SELECT id FROM watches WHERE address=?)"
                            else->null
                        }
                        if(dependency!=null)check(!db.rawQuery(dependency,key).use{it.moveToFirst()}){"Later catalog references prevent rollback"}
                        check(db.delete(t.name,t.keys.joinToString(" AND "){it+"=?"},key)==1);removed++
                    }
                }
                db.delete("nexus_donor_import_rows","digest=?",arrayOf(digest))
                db.delete("nexus_donor_imports","digest=?",arrayOf(digest))
                db.setTransactionSuccessful()
                return removed
            }finally{db.endTransaction()}
        }
    }
}
