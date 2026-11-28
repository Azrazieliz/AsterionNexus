package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.azrael.galleryflow.GalleryDb

/**
 * One shared persistent outbox for Kuroha & Kyora. Downloads are never deleted,
 * nor are credentials stored in this database. Only successful create/rapid
 * upload API responses mark a file uploaded.
 */
class NduCloudQueue(context: Context) :
    SQLiteOpenHelper(context,"nocturne_ndus_queue.db",null,4) {
    private val app=context.applicationContext
    data class Job(val category:String,val name:String,val uri:String,
                   val attempts:Int,val state:String,val accountScope:String="")
    data class Status(val pending:Int,val completed:Int,val lastError:String?,
                      val verificationPending:Int=0,val lastVerifiedPath:String?=null)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs(
            category TEXT NOT NULL,
            name TEXT NOT NULL,
            content_uri TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            remote_path TEXT,remote_size INTEGER,remote_fs_id INTEGER,
            remote_md5 TEXT,verified_at INTEGER,
            account_scope TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(category,content_uri,account_scope)
        )""")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..3&&newVersion==4){"Unsupported upload ledger migration"}
        val columns=mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(jobs)",null).use{c->while(c.moveToNext())columns.add(c.getString(1))}
        db.execSQL("ALTER TABLE jobs RENAME TO jobs_legacy")
        onCreate(db)
        val fields=listOf("category","name","content_uri","state","attempts","retry_after","last_error",
            "remote_path","remote_size","remote_fs_id","remote_md5","verified_at")
        val select=fields.joinToString(","){name->when{
            name=="state"->"CASE WHEN state='UPLOADED' THEN 'VERIFY_PENDING' ELSE state END"
            name in columns->name
            name in setOf("attempts","retry_after")->"0"
            else->"NULL"
        }}
        // Unscoped legacy rows retain their evidence but are held until ownership is established.
        db.execSQL("INSERT INTO jobs("+fields.joinToString(",")+") SELECT "+select+" FROM jobs_legacy")
        db.execSQL("DROP TABLE jobs_legacy")
    }
    @Synchronized fun enqueue(category:String,name:String,uri:Uri) {
        enqueueScoped(category,name,uri,NduSettings.accountScope(app))
    }
    @Synchronized internal fun enqueueScoped(category:String,name:String,uri:Uri,scope:String) {
        require(category in setOf("kuroha","kyora","x","comics","videos",
            "books","games","assets","koikatsu","images","torrents","creators","originals"))
        NduTeraboxClient.safeFileName(name)
        require(uri.scheme=="content") { "Only locally owned files may be queued" }
        val row=ContentValues().apply {
            put("account_scope",scope)
            put("category",category);put("name",name);put("content_uri",uri.toString())
            put("state","PENDING")
        }
        writableDatabase.insertWithOnConflict("jobs",null,row,SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun nextBatch(limit:Int=100,nexusOnly:Boolean=false):List<Job> =
        nextBatchScoped(NduSettings.accountScope(app),limit,nexusOnly)
    @Synchronized internal fun nextBatchScoped(scope:String,limit:Int,nexusOnly:Boolean):List<Job> {
        if(scope.isBlank())return emptyList()
        val result=mutableListOf<Job>()
        readableDatabase.query("jobs",
            arrayOf("category","name","content_uri","attempts","state","account_scope"),
            "state IN ('PENDING','VERIFY_PENDING') AND retry_after<=? AND account_scope=?"+
                (if(nexusOnly)" AND content_uri LIKE 'content://"+app.packageName+".library/original/%'" else ""),
            arrayOf(System.currentTimeMillis().toString(),scope),null,null,
            "CASE WHEN state='VERIFY_PENDING' THEN 0 ELSE 1 END, attempts ASC,category ASC,name ASC",
            limit.coerceIn(1,250).toString()
        ).use { c ->
            while(c.moveToNext()) result+=Job(c.getString(0),c.getString(1),
                c.getString(2),c.getInt(3),c.getString(4),c.getString(5))
        }
        return result
    }
    @Synchronized fun completed(job:Job,proof:NduRemoteReceipt) {
        val suffix="/"+NduTeraboxClient.safeFileName(job.name)
        require(proof.size>0L && proof.fsId>0L && proof.path.endsWith(suffix)){
            "Cloud receipt does not match the queued file"
        }
        val changed=writableDatabase.update("jobs", ContentValues().apply {
            put("state","VERIFIED");putNull("last_error")
            put("remote_path",proof.path);put("remote_size",proof.size)
            put("remote_fs_id",proof.fsId);put("remote_md5",proof.remoteMd5)
            put("verified_at",proof.verifiedAt);put("retry_after",0L)
        },"category=? AND content_uri=? AND account_scope=?",arrayOf(job.category,job.uri,job.accountScope))
        check(changed==1){"Publication receipt did not match exactly one queued job"}
    }
    @Synchronized fun missingRemote(job:Job){
        writableDatabase.update("jobs",ContentValues().apply{
            put("state","PENDING");put("retry_after",System.currentTimeMillis()+1000L)
            put("last_error","Historical cloud copy not found; upload queued")
        },"category=? AND content_uri=? AND account_scope=? AND state='VERIFY_PENDING'",
            arrayOf(job.category,job.uri,job.accountScope))
    }
    @Synchronized fun failed(job:Job, reason:String) {
        val backoff=minOf(6*60*60_000L,60_000L*(1L shl minOf(job.attempts,7)))
        writableDatabase.update("jobs",ContentValues().apply {
            put("state",if(job.state=="VERIFY_PENDING")"VERIFY_PENDING" else "PENDING")
            put("attempts",job.attempts+1)
            put("retry_after",System.currentTimeMillis()+backoff)
            put("last_error",reason.take(180))
        },"category=? AND content_uri=? AND account_scope=?",arrayOf(job.category,job.uri,job.accountScope))
    }
    /** VERIFIED always has a read-after-write TeraBox receipt. */
    fun stateFor(category:String,uri:String):String?=readableDatabase.rawQuery(
        "SELECT state FROM jobs WHERE category=? AND content_uri=? AND account_scope=? AND account_scope<>'' LIMIT 1",
        arrayOf(category,uri,NduSettings.accountScope(app))).use{c->if(c.moveToFirst())c.getString(0)else null}
    fun receiptFor(uri:String):NduRemoteReceipt?=readableDatabase.rawQuery(
        "SELECT remote_path,remote_size,remote_fs_id,remote_md5,verified_at "+
            "FROM jobs WHERE content_uri=? AND state='VERIFIED' "+
            "AND remote_path IS NOT NULL AND remote_fs_id>0 AND account_scope=? AND account_scope<>'' LIMIT 1",
        arrayOf(uri,NduSettings.accountScope(app))).use{c->
            if(!c.moveToFirst())null
            else NduRemoteReceipt(c.getString(0),c.getLong(1),c.getLong(2),
                c.getString(3),c.getLong(4))
        }
    fun uploadedFor(uri:String):Boolean=receiptFor(uri)!=null
    data class NexusPublication(val uri:String,val name:String,val state:String,
        val error:String?,val size:Long,val path:String?,val scope:String="")
    fun nexusPublications():List<NexusPublication>{
        val rows=mutableListOf<NexusPublication>()
        readableDatabase.rawQuery("SELECT content_uri,name,state,last_error,remote_size,remote_path,account_scope FROM jobs WHERE account_scope<>'' AND content_uri LIKE ? ORDER BY CASE WHEN state='VERIFIED' THEN 1 ELSE 0 END,retry_after ASC,verified_at DESC LIMIT 500",
            arrayOf("content://"+app.packageName+".library/original/%")).use{c->
            while(c.moveToNext())rows.add(NexusPublication(c.getString(0),c.getString(1),c.getString(2),
                c.getString(3),c.getLong(4),c.getString(5),c.getString(6)))
        }
        return rows
    }
    fun hasAnyPublication(uri:String):Boolean=readableDatabase.rawQuery("SELECT 1 FROM jobs WHERE content_uri=? LIMIT 1",arrayOf(uri)).use{it.moveToFirst()}
    fun retryNexus(uri:String){retryNexusScoped(uri,NduSettings.accountScope(app))}
    fun retryNexusScoped(uri:String,scope:String){
        writableDatabase.execSQL("UPDATE jobs SET retry_after=0,last_error=NULL WHERE content_uri=? AND account_scope=? AND account_scope<>'' AND state IN ('PENDING','VERIFY_PENDING')",
            arrayOf(uri,scope))
    }
    internal fun pendingNexus(scope:String):Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE account_scope=? AND state<>'VERIFIED' AND content_uri LIKE ?",arrayOf(scope,"content://"+app.packageName+".library/original/%")).use{if(it.moveToFirst())it.getInt(0)else 0}

    fun status():Status {
        var pending=0;var complete=0;var rechecking=0
        val scope=NduSettings.currentAccountScope(app)
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM jobs WHERE account_scope=? AND account_scope<>'' GROUP BY state",arrayOf(scope)).use { c ->
            while(c.moveToNext()){
                val state=c.getString(0)
                if(state=="VERIFIED")complete+=c.getInt(1)
                else{
                    pending+=c.getInt(1)
                    if(state=="VERIFY_PENDING")rechecking+=c.getInt(1)
                }
            }
        }
        val err=readableDatabase.rawQuery(
            "SELECT last_error FROM jobs WHERE last_error IS NOT NULL AND account_scope=? AND account_scope<>'' " +
                "ORDER BY retry_after DESC LIMIT 1",arrayOf(scope)).use {
            if(it.moveToFirst()) it.getString(0) else null
        }
        val last=readableDatabase.rawQuery(
            "SELECT remote_path FROM jobs WHERE state='VERIFIED' AND account_scope=? AND account_scope<>'' "+
                "ORDER BY verified_at DESC LIMIT 1",arrayOf(scope)).use{
            if(it.moveToFirst())it.getString(0)else null
        }
        return Status(pending,complete,err,rechecking,last)
    }
    /** Scan only files owned by this app, never a user's whole photo library. */
    fun enqueueExisting(ctx:Context, includeKuroha:Boolean=true,
                        includeKyora:Boolean=true):Int {
        var count=0
        if(includeKuroha) {
            // Stream MediaStore rows: do not allocate a 300k+ filename list.
            val relative=android.os.Environment.DIRECTORY_DOWNLOADS + "/" +
                ctx.getString(R.string.pixiv_download_folder) + "/"
            val dirs=if(ctx.resources.getBoolean(R.bool.nocturne_preview))
                listOf(relative) else listOf(relative,"Download/PixiFlow/")
            val pattern=Regex("[0-9]+_p[0-9]+\\.(jpg|jpeg|png|gif|webp)")
            for(dir in dirs) {
                ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.BaseColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME),
                    MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                    arrayOf(dir),null)?.use { c ->
                    while(c.moveToNext()) {
                        val name=c.getString(1)
                        if(name.matches(pattern)) {
                            val uri=android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0))
                            enqueue("kuroha",name,uri)
                            count++
                        }
                    }
                }
            }
        }
        if(includeKyora) GalleryDb(ctx).use { db ->
            // Iterate the MediaStore-owned content URIs, not remote download links.
            // A DB streaming cursor avoids loading a large image library in memory.
            db.readableDatabase.rawQuery(
                "SELECT filename,content_uri FROM media WHERE state='complete' " +
                    "AND content_uri IS NOT NULL AND filename IS NOT NULL " +
                    "GROUP BY filename,content_uri",null
            ).use { c ->
                while(c.moveToNext()) {
                    val name=c.getString(0)
                    val uri=runCatching { Uri.parse(c.getString(1)) }.getOrNull()
                    if(uri?.scheme=="content") {
                        runCatching { enqueue("kyora",name,uri);count++ }
                    }
                }
            }
        }
        // Flat local downloads for X, comics and VideoFlow are also uploaded.
        val ownedFolders=listOf(
            "x" to "Download/AsterionDownloader/X/",
            "comics" to "Download/AsterionDownloader/Comics/",
            "videos" to "Download/VideoFlow/"
        )
        for((category,relative) in ownedFolders) {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.BaseColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                arrayOf(relative),null)?.use { cursor->
                while(cursor.moveToNext()){
                    val name=cursor.getString(1)?:continue
                    val uri=android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,cursor.getLong(0))
                    runCatching{enqueue(category,name,uri);count++}
                }
            }
        }
        return count
    }
}
