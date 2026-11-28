package com.ailm.android.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.UUID

/** Rule-backed collections on the existing Fusion collection tables. The
 * query uses canonical IDs, not display-name substring guesses. No separate
 * image catalog, rating store, or copied media bytes. */
class FusionSmartCollections(private val database:LocalDatabase){
    data class Rule(val kind:String,val value:String,val minRating:Int=-1){
        init{
            require(kind in setOf("character","series","tag","artist","manual_rating","media_type","local_rating","offline_pin","continue_reading","related_output"))
            require(value.length<=128&&!value.any{it.isISOControl()})
            when(kind){
                "manual_rating","local_rating"->require(value.isEmpty()&&minRating in 0..100)
                "media_type"->require(value in setOf("image","video","audio","books","assets")&&minRating==-1)
                "offline_pin","continue_reading","related_output"->require(value.isEmpty()&&minRating==-1)
                else->require(value.isNotBlank()&&minRating in -1..100)
            }
        }
        val coreRule:Boolean get()=kind in setOf("character","series","tag","artist","manual_rating")
        fun json()=JSONObject().put("version","nexus.smart.v1").put("kind",kind).put("value",value)
            .put("minimum_manual_rating",minRating).toString()
        companion object{
            fun parse(raw:String):Rule?=runCatching{
                val j=JSONObject(raw)
                require(j.getString("version")=="nexus.smart.v1")
                Rule(j.getString("kind"),j.getString("value"),j.getInt("minimum_manual_rating"))
            }.getOrNull()
        }
    }
    data class Option(val id:String,val title:String)
    data class Collection(val id:String,val name:String,val kind:String,val rule:Rule?,val supported:Boolean)
    data class Entry(val imageId:Int,val uri:String,val filename:String,val mime:String)
    data class Page(val entries:List<Entry>,val next:Int?)
    private fun catalog(kind:String):Triple<String,String,String> = when(kind){
        "character"->Triple("fusion_characters","character_id","canonical_name")
        "series"->Triple("fusion_series","series_code","canonical_title")
        "tag"->Triple("fusion_tags","tag_id","canonical_name")
        "artist"->Triple("fusion_artists","artist_id","display_name")
        else->throw IllegalArgumentException("Invalid canonical rule")
    }
    fun options(kind:String,query:String):List<Option>{
        val (table,id,name)=catalog(kind)
        val escaped=query.trim().take(128).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
        return database.readableDatabase.rawQuery("SELECT "+id+","+name+" FROM "+table+
            " WHERE "+name+" LIKE ? ESCAPE '\\' OR "+id+"=? ORDER BY "+name+" COLLATE NOCASE,"+id+" LIMIT 60",
            arrayOf("%"+escaped+"%",query.trim())).use{c->
            buildList{while(c.moveToNext())add(Option(c.getString(0),c.getString(1)))}
        }
    }
    fun list(limit:Int=100,offset:Int=0):List<Collection>{
        require(limit in 1..200&&offset>=0)
        return database.readableDatabase.rawQuery("SELECT collection_id,collection_name,collection_kind,smart_filter_json FROM fusion_collections ORDER BY collection_name COLLATE NOCASE,collection_id LIMIT ? OFFSET ?",arrayOf(limit.toString(),offset.toString())).use{c->
            buildList{while(c.moveToNext()){
                val kind=c.getString(2);val rule=Rule.parse(c.getString(3))
                add(Collection(c.getString(0),c.getString(1),kind,rule,kind=="manual"||rule!=null))
            }}
        }
    }
    fun create(name:String,rule:Rule?):String{
        val title=name.trim()
        require(title.length in 1..100&&!title.any{it.isISOControl()})
        val db=database.writableDatabase
        if(rule!=null&&rule.coreRule&&rule.kind!="manual_rating"){
            val (table,id,_)=catalog(rule.kind)
            require(db.rawQuery("SELECT 1 FROM "+table+" WHERE "+id+"=?",arrayOf(rule.value)).use{it.moveToFirst()}){"Canonical identity no longer exists"}
        }
        val id="nexus-"+UUID.randomUUID()
        val now=System.currentTimeMillis()
        db.insertOrThrow("fusion_collections",null,ContentValues().apply{
            put("collection_id",id);put("collection_name",title)
            put("collection_kind",if(rule==null)"manual" else "smart")
            put("smart_filter_json",rule?.json()?:"{}")
            put("metadata_json",JSONObject().put("origin","nexus").toString())
            put("created_at_ms",now);put("updated_at_ms",now)
        })
        return id
    }
    private fun get(db:SQLiteDatabase,id:String):Collection{
        return db.rawQuery("SELECT collection_name,collection_kind,smart_filter_json FROM fusion_collections WHERE collection_id=?",arrayOf(id)).use{c->
            check(c.moveToFirst()){"Collection no longer exists"}
            val kind=c.getString(1);val rule=Rule.parse(c.getString(2))
            Collection(id,c.getString(0),kind,rule,kind=="manual"||rule!=null)
        }
    }
    internal fun predicate(rule:Rule):Pair<String,List<String>>{
        val clauses=ArrayList<String>();val args=ArrayList<String>()
        when(rule.kind){
            "tag"->{
                clauses.add("i.image_id IN (SELECT image_id FROM fusion_image_canonical_tags WHERE tag_id=? AND tag_scope<>'character_reference' AND (source='manual' OR confidence>=0.65))")
                args.add(rule.value)
            }
            "character","series","artist"->{
                val (table,column)=when(rule.kind){
                    "character"->"fusion_image_characters" to "character_id"
                    "series"->"fusion_image_series" to "series_code"
                    else->"fusion_image_artists" to "artist_id"
                }
                clauses.add("i.image_id IN (SELECT image_id FROM "+table+" WHERE "+column+"=? AND (source='manual' OR confidence>=0.65))")
                args.add(rule.value)
            }
        }
        if(rule.minRating>=0){
            // Distinguish an explicitly saved zero from the default zero.
            clauses.add("i.image_id IN (SELECT image_id FROM fusion_image_profiles WHERE rating>=? AND CASE WHEN json_valid(metadata_json) THEN json_extract(metadata_json,'$.precision_overall_set')=1 ELSE 0 END)")
            args.add(rule.minRating.toString())
        }
        check(clauses.isNotEmpty())
        return clauses.joinToString(" AND ") to args
    }
    fun page(id:String,after:Int=0,limit:Int=60):Page{
        require(after>=0&&limit in 1..100)
        val db=database.readableDatabase
        val collection=get(db,id)
        check(collection.supported){"Existing collection rule format is not supported by this viewer"}
        if(collection.kind!="manual"&&collection.rule?.coreRule==false)return Page(emptyList(),null)
        val (where,args)=if(collection.kind=="manual")
            "i.image_id IN (SELECT image_id FROM fusion_collection_images WHERE collection_id=?)" to listOf(id)
        else predicate(collection.rule!!)
        return query(db,where,args,after,limit)
    }
    fun previewRule(rule:Rule,after:Int,limit:Int):Page{
        require(rule.coreRule&&after>=0&&limit in 1..100)
        val (where,args)=predicate(rule)
        return query(database.readableDatabase,where,args,after,limit)
    }
    private fun query(db:SQLiteDatabase,where:String,args:List<String>,after:Int,limit:Int):Page{
        val rows=db.rawQuery("SELECT i.image_id,i.uri,i.filename,i.mime_type FROM images i WHERE i.active=1 AND i.image_id>? AND "+where+" ORDER BY i.image_id LIMIT ?",
            (listOf(after.toString())+args+(limit+1).toString()).toTypedArray()).use{c->
            buildList{while(c.moveToNext())add(Entry(c.getInt(0),c.getString(1),c.getString(2),c.getString(3)?:"image/*"))}
        }
        val items=rows.take(limit)
        return Page(items,if(rows.size>limit)items.last().imageId else null)
    }
    fun add(id:String,imageId:Int){
        require(imageId>0)
        val db=database.writableDatabase
        db.beginTransactionNonExclusive()
        try{
            check(get(db,id).kind=="manual"){"Smart collection membership is determined by its rule"}
            check(db.rawQuery("SELECT 1 FROM images WHERE image_id=? AND active=1",arrayOf(imageId.toString())).use{it.moveToFirst()}){"Artwork not indexed"}
            db.execSQL("INSERT OR IGNORE INTO fusion_collection_images(collection_id,image_id,ordinal,added_at_ms) SELECT ?,?,COALESCE(MAX(ordinal),-1)+1,? FROM fusion_collection_images WHERE collection_id=?",arrayOf(id,imageId,System.currentTimeMillis(),id))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun remove(id:String,imageId:Int){
        val db=database.writableDatabase
        db.beginTransactionNonExclusive()
        try{
            check(get(db,id).kind=="manual")
            db.delete("fusion_collection_images","collection_id=? AND image_id=?",arrayOf(id,imageId.toString()))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
}
