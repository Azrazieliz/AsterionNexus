package com.asterion.nexus;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.*;
import java.util.*;

/** Canonical local-original registry. Physical bytes are never overwritten or moved from donors. */
public final class NexusRegistry {
    private final Context app;
    private final File privateRoot;
    private static final String DB_NAME="nexus_registry_v1.sqlite";

    private static final class Database extends SQLiteOpenHelper {
        Database(Context app){super(app,DB_NAME,null,2);setWriteAheadLoggingEnabled(true);}
        @Override public void onConfigure(SQLiteDatabase db){
            super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);
        }
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE originals(sha256 TEXT PRIMARY KEY,byte_length INTEGER NOT NULL CHECK(byte_length>0),mime TEXT NOT NULL,verified_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE locations(id INTEGER PRIMARY KEY AUTOINCREMENT,sha256 TEXT NOT NULL REFERENCES originals(sha256),source_kind TEXT NOT NULL,account_key TEXT NOT NULL,origin_key TEXT NOT NULL,display_name TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(sha256,source_kind,account_key,origin_key))");
            db.execSQL("CREATE INDEX idx_nexus_library_names ON locations(display_name COLLATE NOCASE)");
            db.execSQL("CREATE INDEX idx_nexus_library_sources ON locations(source_kind,account_key)");
            db.execSQL("CREATE TABLE jobs(job_key TEXT PRIMARY KEY,stage TEXT NOT NULL,received INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,error_code TEXT)");
            createFileRatings(db);
        }
        private void createFileRatings(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS file_ratings(sha256 TEXT PRIMARY KEY CHECK(length(sha256)=64),score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 100),updated_at INTEGER NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int old,int current){
            if(old==1&&current==2){createFileRatings(db);return;}
            throw new IllegalStateException("Unsupported Nexus registry migration");
        }
    }
    public static final class Item {
        public final long locationId;
        public final String sha256,displayName,mime,sourceKind,accountKey;
        public final long length;
        Item(long id,String sha,String name,String mime,String source,String account,long length){
            locationId=id;sha256=sha;displayName=name;this.mime=mime;
            sourceKind=source;accountKey=account;this.length=length;
        }
    }
    public NexusRegistry(Context context){
        app=context.getApplicationContext();
        privateRoot=new File(app.getFilesDir(),"nexus-library");
    }
    private static String label(String input,String fallback){
        if(input==null)return fallback;
        String clean=input.replaceAll("[\\p{Cntrl}]+"," ").trim();
        return clean.isEmpty()?fallback:clean.substring(0,Math.min(256,clean.length()));
    }
    public Item ingest(InputStream input,String displayName,String mime,String sourceKind,
                       String accountKey,String sourceIdentifier,long expectedLength,String expectedSha)
                       throws IOException {
        if(sourceIdentifier==null||sourceIdentifier.isBlank())
            throw new IllegalArgumentException("Selected source identity required");
        String kind=label(sourceKind,"local").toLowerCase(Locale.ROOT);
        String account=label(accountKey,"device");
        String origin=NexusOriginalStore.sourceKey(sourceIdentifier);
        String type=label(mime,"application/octet-stream").toLowerCase(Locale.ROOT);
        if(!type.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))
            type="application/octet-stream";
        if(type.equals("application/octet-stream")){
            String filename=label(displayName,"").toLowerCase(Locale.ROOT);
            if(filename.endsWith(".epub"))type="application/epub+zip";
            else if(filename.endsWith(".docx"))
                type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            else if(filename.endsWith(".cbz"))
                type="application/vnd.comicbook+zip";
            else if(filename.endsWith(".pdf"))type="application/pdf";
        }
        NexusOriginalStore.VerifiedFile bytes=NexusOriginalStore.ingest(
                input,privateRoot,expectedLength,expectedSha);
        long now=System.currentTimeMillis();
        Database helper=new Database(app);
        try {
            SQLiteDatabase db=helper.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues original=new ContentValues();
                original.put("sha256",bytes.sha256);original.put("byte_length",bytes.length);
                original.put("mime",type);original.put("verified_at",now);
                db.insertWithOnConflict("originals",null,original,SQLiteDatabase.CONFLICT_IGNORE);
                ContentValues location=new ContentValues();
                location.put("sha256",bytes.sha256);
                location.put("source_kind",kind);location.put("account_key",account);
                location.put("origin_key",origin);
                location.put("display_name",label(displayName,bytes.sha256));
                location.put("created_at",now);
                db.insertWithOnConflict("locations",null,location,SQLiteDatabase.CONFLICT_IGNORE);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            try(Cursor c=db.rawQuery("SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.sha256=? AND l.source_kind=? AND l.account_key=? AND l.origin_key=? LIMIT 1",new String[]{bytes.sha256,kind,account,origin})){
                if(!c.moveToFirst())throw new IOException("Verified original not indexed");
                Item verified=item(c);
                // Index only supported original book text after byte verification.
                // Search-index scheduling is best effort; never invalidate the saved original.
                try{new NexusBookIndex(app).enqueue(verified);}
                catch(RuntimeException ignored){}
                return verified;
            }
        }finally{helper.close();}
    }
    public List<Item> search(String query,int limit){
        return searchPage(query,0,limit);
    }
    /** Bounded, stable, non-destructive paging over 300k+ metadata rows. */
    public List<Item> searchPage(String query,int offset,int limit){
        return searchPage(query,offset,limit,"All");
    }

    /**
     * Filter before pagination, never after returning the first batch. This
     * ensures media tabs work correctly for large multi-format libraries.
     * The owner-facing category maps to fixed SQL predicates only.
     */
    public List<Item> searchPage(String query,int offset,int limit,String category){
        if(limit<1||limit>200||offset<0||offset>1000000)
            throw new IllegalArgumentException("Invalid library page");
        String needle=query==null?"":query.toLowerCase(Locale.ROOT);
        needle=needle.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        String book="(o.mime LIKE '%pdf%' OR o.mime LIKE '%epub%' OR "+
                "o.mime LIKE '%comic%' OR o.mime LIKE '%wordprocessingml%')";
        String restriction;
        switch(category==null?"All":category){
            case "Images":restriction=" AND o.mime LIKE 'image/%'";break;
            case "Videos":restriction=" AND o.mime LIKE 'video/%'";break;
            case "Audio":restriction=" AND o.mime LIKE 'audio/%'";break;
            case "Books":restriction=" AND "+book;break;
            case "Assets":restriction=" AND NOT (o.mime LIKE 'image/%' OR "+
                    "o.mime LIKE 'video/%' OR "+book+")";break;
            case "Other":restriction=" AND NOT (o.mime LIKE 'image/%' OR "+
                    "o.mime LIKE 'video/%' OR o.mime LIKE 'audio/%' OR "+
                    book+")";break;
            default:restriction="";
        }
        ArrayList<Item> found=new ArrayList<>();
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length "+
            "FROM locations l JOIN originals o ON l.sha256=o.sha256 "+
            "WHERE LOWER(l.display_name) LIKE ? ESCAPE '\\' "+restriction+
            " ORDER BY l.created_at DESC,l.id DESC LIMIT ? OFFSET ?",
            new String[]{"%"+needle+"%",Integer.toString(limit),Integer.toString(offset)})){
            while(c.moveToNext())found.add(item(c));
        }finally{helper.close();}
        return found;
    }
    public List<Item> acquiredPage(int offset,int limit){
        if(offset<0||limit<1||limit>200)throw new IllegalArgumentException("Invalid staged page");
        ArrayList<Item> rows=new ArrayList<>();Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.source_kind IN ('downloader-direct','nexus-comic','nexus-novel','nexus-assembly') ORDER BY l.id LIMIT ? OFFSET ?",
            new String[]{Integer.toString(limit),Integer.toString(offset)})){
            while(c.moveToNext())rows.add(item(c));
        }finally{helper.close();}
        return rows;
    }
    public Item find(String sha){
        if(sha==null||!sha.matches("[0-9a-f]{64}"))return null;
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.sha256=? LIMIT 1",
            new String[]{sha})){
            return c.moveToFirst()?item(c):null;
        }finally{helper.close();}
    }
    private static Item item(Cursor c){
        return new Item(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),
                c.getString(4),c.getString(5),c.getLong(6));
    }
    private static void requireRatingIdentity(String sha){
        if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Verified content identity required");
    }
    public int fileRating(String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT score FROM file_ratings WHERE sha256=?",new String[]{sha})){
            return c.moveToFirst()?c.getInt(0):-1;
        }finally{helper.close();}
    }
    public void setFileRating(String sha,int score){
        requireRatingIdentity(sha);
        if(score < -1 || score > 100)throw new IllegalArgumentException("Invalid manual rating");
        Database helper=new Database(app);
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            if(score==-1){db.delete("file_ratings","sha256=?",new String[]{sha});return;}
            ContentValues row=new ContentValues();
            row.put("sha256",sha);row.put("score",score);row.put("updated_at",System.currentTimeMillis());
            if(db.insertWithOnConflict("file_ratings",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("File rating was not stored");
        }finally{helper.close();}
    }
    public File original(Item item) throws IOException{
        if(item==null||!item.sha256.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid library identity");
        File file=new File(new File(privateRoot,"originals"),item.sha256);
        if(!file.isFile()||file.length()!=item.length)
            throw new IOException("Verified original unavailable or modified");
        return file;
    }
    public void job(String sourceIdentifier,String stage,long received,String errorCode){
        if(!Set.of("QUEUED","RECEIVING","BYTES_COMPLETE","VERIFIED","INDEXED","FAILED")
            .contains(stage))throw new IllegalArgumentException("Unknown job stage");
        if(received<0)throw new IllegalArgumentException("Negative byte count");
        long now=System.currentTimeMillis();
        ContentValues row=new ContentValues();
        row.put("job_key",NexusOriginalStore.sourceKey(sourceIdentifier));
        row.put("stage",stage);row.put("received",received);
        row.put("created_at",now);row.put("updated_at",now);
        row.put("error_code",label(errorCode,""));
        Database helper=new Database(app);
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            db.execSQL("INSERT OR IGNORE INTO jobs(job_key,stage,received,created_at,updated_at,error_code) VALUES(?,?,?,?,?,?)",
                new Object[]{row.getAsString("job_key"),"QUEUED",0,now,now,""});
            db.execSQL("UPDATE jobs SET stage=?,received=?,updated_at=?,error_code=? WHERE job_key=?",
                new Object[]{stage,received,now,row.getAsString("error_code"),row.getAsString("job_key")});
        }finally{helper.close();}
    }
}
