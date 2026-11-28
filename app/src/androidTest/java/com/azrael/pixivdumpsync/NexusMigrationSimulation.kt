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
        verifyCoreRecovery(instrumentation,status)
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

    /** Reproduce the reported same-version and upgraded legacy revision-table
     * loss using an isolated actual Core SQLite database, never owner data. */
    private fun verifyCoreRecovery(instrumentation:Instrumentation,status:(String)->Unit){
        val context=instrumentation.targetContext
        val name="nexus-core-regression-"+java.util.UUID.randomUUID()+".sqlite"
        fun revision(db:SQLiteDatabase):Long=db.rawQuery("SELECT revision FROM fusion_taxonomy_revision WHERE id=1",null).use{
            check(it.moveToFirst()){"Missing taxonomy revision row"};it.getLong(0)
        }
        fun rating(db:SQLiteDatabase,table:String):Int=db.rawQuery("SELECT rating FROM "+table+" WHERE image_id=1",null).use{
            check(it.moveToFirst()){"Lost manual artwork rating in "+table};it.getInt(0)
        }
        try{
            com.ailm.android.runtime.LocalDatabase(context,name).use{helper->
                val db=helper.writableDatabase
                db.execSQL("INSERT INTO images(image_id,uri,filename,folder_uri,imported_order,rating,scanned_at_ms) VALUES(1,'content://fixture/artwork','source.png','content://fixture',1,73,1)")
                db.execSQL("INSERT INTO fusion_tags(tag_id,canonical_name,created_at_ms,updated_at_ms) VALUES('fixture-tag','Fixture tag',1,1)")
                check(revision(db)>0L)
                check(rating(db,"images")==73&&rating(db,"fusion_image_profiles")==73)
                db.execSQL("DROP TABLE fusion_taxonomy_revision")
                // Reopen with exactly the same schema version (the owner failure).
            }
            com.ailm.android.runtime.LocalDatabase(context,name).use{helper->
                val db=helper.writableDatabase
                val before=revision(db)
                check(rating(db,"images")==73&&rating(db,"fusion_image_profiles")==73)
                db.execSQL("UPDATE fusion_tags SET canonical_name='Recovered fixture' WHERE tag_id='fixture-tag'")
                check(revision(db)==before+1)
                db.execSQL("DROP TABLE fusion_taxonomy_revision")
                db.version=17
            }
            com.ailm.android.runtime.LocalDatabase(context,name).use{helper->
                val db=helper.writableDatabase
                val before=revision(db)
                check(db.version==18)
                check(rating(db,"images")==73&&rating(db,"fusion_image_profiles")==73)
                db.execSQL("INSERT INTO fusion_tag_aliases(alias_id,tag_id,alias_value,created_at_ms) VALUES('fixture-alias','fixture-tag','Fixture alias',1)")
                check(revision(db)==before+1)
                check(db.rawQuery("SELECT COUNT(*) FROM fusion_tags WHERE tag_id='fixture-tag'",null).use{it.moveToFirst();it.getInt(0)}==1)
            }
            com.ailm.android.runtime.LocalDatabase(context,name).use{helper->
                check(revision(helper.writableDatabase)>0)
                check(rating(helper.readableDatabase,"images")==73&&rating(helper.readableDatabase,"fusion_image_profiles")==73)
            }
            status("CORE_LEGACY_RECOVERY_OK missing taxonomy table, revision triggers, upgrade and preserved manual ratings")
        }finally{context.deleteDatabase(name)}
    }
}
