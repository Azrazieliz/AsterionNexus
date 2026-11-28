package com.azrael.pixivdumpsync
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import com.asterion.nexus.NexusDonorSqliteMigration
import com.azrael.galleryflow.LegacyCatalogMigration
import java.io.File
import java.security.MessageDigest

internal object NexusMigrationSimulation {
    fun run(instrumentation:Instrumentation,status:(String)->Unit){
        val context=instrumentation.targetContext
        val file=File(context.cacheDir,"nexus-donor-fixture.sqlite")
        file.delete()
        val userId="90000000000001"
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.execSQL("CREATE TABLE artists(user_id TEXT PRIMARY KEY,label TEXT,selected INTEGER,live_enabled INTEGER,archive_status TEXT,known_total INTEGER)")
            db.execSQL("CREATE TABLE works(illust_id TEXT PRIMARY KEY,artist_id TEXT,title TEXT,state TEXT,page_count INTEGER,updated_at INTEGER)")
            db.execSQL("CREATE TABLE pages(illust_id TEXT,page_index INTEGER,filename TEXT,saved INTEGER,PRIMARY KEY(illust_id,page_index))")
            db.execSQL("INSERT INTO artists VALUES(?, 'Synthetic artist',1,1,'COMPLETE',1)",arrayOf(userId))
            db.execSQL("INSERT INTO works VALUES('90000000000002',?,'Synthetic source','DONE',1,1)",arrayOf(userId))
            db.execSQL("INSERT INTO pages VALUES('90000000000002',0,'source.png',1)")
        }
        val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val snapshot=LegacyCatalogMigration.Staged(file,sha,file.length())
        val migration=NexusDonorSqliteMigration(context)
        try{
            check(AppDb(context).use{it.artist(userId)}==null){"Fixture source already exists"}
            val dry=migration.process(snapshot,false)
            check(dry.seen==3L&&dry.inserted==1L&&dry.held==2L&&!dry.committed)
            check(AppDb(context).use{it.artist(userId)}==null){"Dry run mutated catalog"}
            val applied=migration.process(snapshot,true)
            check(applied.committed&&applied.inserted==1L&&applied.held==2L)
            val actual=AppDb(context).use{it.artist(userId)}?:error("Imported subscription missing")
            check(!actual.selected&&!actual.liveEnabled&&actual.archiveStatus=="NOT_STARTED")
            check(!AppDb(context).use{it.hasWorkRecord("90000000000002")}){"Unverified completion replayed"}
            check(migration.process(snapshot,true).inserted==1L)
            check(migration.rollback("Pixiv",sha)==1L)
            check(AppDb(context).use{it.artist(userId)}==null)
            status("DONOR_MIGRATION_OK actual SQLite dry run, disabled subscription, history, idempotency and rollback")
        }finally{file.delete()}
    }
}
