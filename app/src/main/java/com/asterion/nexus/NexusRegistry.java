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
        Database(Context app){super(app,DB_NAME,null,3);setWriteAheadLoggingEnabled(true);}
        @Override public void onConfigure(SQLiteDatabase db){
            super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);
        }
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE originals(sha256 TEXT PRIMARY KEY,byte_length INTEGER NOT NULL CHECK(byte_length>0),mime TEXT NOT NULL,verified_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE locations(id INTEGER PRIMARY KEY AUTOINCREMENT,sha256 TEXT NOT NULL REFERENCES originals(sha256),source_kind TEXT NOT NULL,account_key TEXT NOT NULL,origin_key TEXT NOT NULL,display_name TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(sha256,source_kind,account_key,origin_key))");
            db.execSQL("CREATE INDEX idx_nexus_library_names ON locations(display_name COLLATE NOCASE)");
            db.execSQL("CREATE INDEX idx_nexus_library_sources ON locations(source_kind,account_key)");
            db.execSQL("CREATE TABLE jobs(job_key TEXT PRIMARY KEY,stage TEXT NOT NULL,received INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,error_code TEXT)");
            createFileRatings(db);createRemoteLocations(db);
        }
        private void createFileRatings(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS file_ratings(sha256 TEXT PRIMARY KEY CHECK(length(sha256)=64),score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 100),updated_at INTEGER NOT NULL)");
        }
        private void createRemoteLocations(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_locations(account TEXT NOT NULL,fs_id INTEGER NOT NULL CHECK(fs_id>0),path TEXT NOT NULL,bytes INTEGER NOT NULL CHECK(bytes>0),remote_md5 TEXT NOT NULL DEFAULT '',sha256 TEXT NOT NULL REFERENCES originals(sha256),pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),verified_at INTEGER NOT NULL,PRIMARY KEY(account,fs_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nexus_remote_sha ON remote_locations(sha256,account)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nexus_remote_path ON remote_locations(account,path)");
            db.execSQL("CREATE TABLE IF NOT EXISTS content_relations(output_sha TEXT NOT NULL REFERENCES originals(sha256),source_sha TEXT NOT NULL REFERENCES originals(sha256),kind TEXT NOT NULL,operation TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(output_sha,source_sha,kind,operation))");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int old,int current){
            if(old<1||current!=3||old>=current)throw new IllegalStateException("Unsupported Nexus registry migration");
            if(old==1)createFileRatings(db);
            createRemoteLocations(db);
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
            else if(filename.endsWith(".cbr"))type="application/vnd.comicbook-rar";
        }
        type=canonicalComicMime(displayName,type);
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
                "o.mime LIKE '%comic%' OR o.mime='application/x-cbz' OR o.mime='application/x-cbr' OR LOWER(l.display_name) LIKE '%.cbr' OR o.mime LIKE '%wordprocessingml%' OR o.mime='text/plain')";
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

    public static final class RemoteLocation {
        public final String account,path,md5,sha256;
        public final long fsId,length,verifiedAt;
        public final boolean pinned;
        RemoteLocation(Cursor c){
            account=c.getString(0);fsId=c.getLong(1);path=c.getString(2);length=c.getLong(3);
            md5=c.getString(4);sha256=c.getString(5);pinned=c.getInt(6)!=0;verifiedAt=c.getLong(7);
        }
    }
    private static void remoteIdentity(String account,long fsId,String path,long bytes,String md5){
        if(account==null||!account.matches("[0-9a-f-]{36}")||fsId<=0||path==null||!path.startsWith("/")||
            path.length()>4096||bytes<=0||md5==null||(!md5.isEmpty()&&!md5.matches("[0-9a-f]{32}")))
            throw new IllegalArgumentException("Invalid remote location identity");
    }
    public RemoteLocation remote(String account,long fsId){
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT account,fs_id,path,bytes,remote_md5,sha256,pinned,verified_at FROM remote_locations WHERE account=? AND fs_id=?",
            new String[]{account,Long.toString(fsId)})){
            return c.moveToFirst()?new RemoteLocation(c):null;
        }finally{helper.close();}
    }
    public void bindRemote(Item item,String account,long fsId,String path,long bytes,String md5,boolean pinned)throws IOException{
        remoteIdentity(account,fsId,path,bytes,md5);
        if(item==null||item.length!=bytes||!verifyOriginal(item))throw new IOException("Verified content required for remote binding");
        Database helper=new Database(app);
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            try(Cursor c=db.rawQuery("SELECT sha256,bytes,remote_md5,pinned FROM remote_locations WHERE account=? AND fs_id=?",
                new String[]{account,Long.toString(fsId)})){
                if(c.moveToFirst()){
                    if(!item.sha256.equals(c.getString(0))||bytes!=c.getLong(1)||
                        (!md5.isEmpty()&&!c.getString(2).isEmpty()&&!md5.equals(c.getString(2))))
                        throw new IOException("Remote object identity changed; existing binding retained");
                    pinned=pinned||c.getInt(3)!=0;
                    if(md5.isEmpty())md5=c.getString(2);
                }
            }
            ContentValues row=new ContentValues();
            row.put("account",account);row.put("fs_id",fsId);row.put("path",path);row.put("bytes",bytes);
            row.put("remote_md5",md5);row.put("sha256",item.sha256);row.put("pinned",pinned?1:0);
            row.put("verified_at",System.currentTimeMillis());
            if(db.insertWithOnConflict("remote_locations",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IOException("Remote location not recorded");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();helper.close();}
    }
    public void noteRemoteMove(String account,long fsId,String oldPath,String newPath,long bytes,String md5){
        remoteIdentity(account,fsId,newPath,bytes,md5);
        Database helper=new Database(app);
        try{
            helper.getWritableDatabase().execSQL("UPDATE remote_locations SET path=?,verified_at=? WHERE account=? AND fs_id=? AND bytes=? AND path=? AND (remote_md5='' OR ?='' OR remote_md5=?)",
                new Object[]{newPath,System.currentTimeMillis(),account,fsId,bytes,oldPath,md5,md5});
        }finally{helper.close();}
    }
    /** An unpin never deletes bytes. Removal requires the separate verified
     * cleanup proposal, which is not inferred from a missing remote record. */
    public void unpin(String account,long fsId){
        Database helper=new Database(app);
        try{helper.getWritableDatabase().execSQL("UPDATE remote_locations SET pinned=0 WHERE account=? AND fs_id=?",new Object[]{account,fsId});}
        finally{helper.close();}
    }
    public List<RemoteLocation> pinned(int limit,int offset){
        if(limit<1||limit>200||offset<0)throw new IllegalArgumentException("Invalid pin page");
        Database helper=new Database(app);ArrayList<RemoteLocation> rows=new ArrayList<>();
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT account,fs_id,path,bytes,remote_md5,sha256,pinned,verified_at FROM remote_locations WHERE pinned=1 ORDER BY verified_at DESC,account,fs_id LIMIT ? OFFSET ?",new String[]{Integer.toString(limit),Integer.toString(offset)})){
            while(c.moveToNext())rows.add(new RemoteLocation(c));
        }finally{helper.close();}
        return rows;
    }
    public void linkDerivative(String output,String source,String kind,String operation){
        if(output==null||source==null||!output.matches("[0-9a-f]{64}")||!source.matches("[0-9a-f]{64}")||
            !Set.of("derived_from","translated_from","extracted_from","assembled_from").contains(kind)||operation==null||operation.isBlank())
            throw new IllegalArgumentException("Invalid content relation");
        if(output.equals(source))return;
        Database helper=new Database(app);
        try{
            helper.getWritableDatabase().execSQL("INSERT OR IGNORE INTO content_relations(output_sha,source_sha,kind,operation,created_at) VALUES(?,?,?,?,?)",
                new Object[]{output,source,kind,NexusOriginalStore.sourceKey(operation),System.currentTimeMillis()});
        }finally{helper.close();}
    }
    public List<Item> related(String sha,int limit){
        requireRatingIdentity(sha);
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid related page");
        Database helper=new Database(app);ArrayList<Item> rows=new ArrayList<>();
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON o.sha256=l.sha256 WHERE l.id IN (SELECT MIN(l2.id) FROM locations l2 WHERE l2.sha256 IN (SELECT output_sha FROM content_relations WHERE source_sha=? UNION SELECT source_sha FROM content_relations WHERE output_sha=?) GROUP BY l2.sha256) ORDER BY l.id DESC LIMIT ?",new String[]{sha,sha,Integer.toString(limit)})){
            while(c.moveToNext())rows.add(item(c));
        }finally{helper.close();}
        return rows;
    }

    public static final class BookSeedPage {
        public final List<Item> items;
        public final long cursor;
        public final boolean more;
        BookSeedPage(List<Item> items,long cursor,boolean more){this.items=items;this.cursor=cursor;this.more=more;}
    }
    /** Capture the high-water mark before scanning so concurrent imports
     * cannot be skipped. Historical books are seeded without rewriting data. */
    public BookSeedPage bookCandidatesAfter(long after,int limit){
        if(after<0||limit<1||limit>200)throw new IllegalArgumentException("Invalid seed cursor");
        ArrayList<Item> items=new ArrayList<>();
        Database helper=new Database(app);
        try{
            SQLiteDatabase db=helper.getReadableDatabase();
            long ceiling=after;
            try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM locations",null)){
                if(c.moveToFirst())ceiling=Math.max(after,c.getLong(0));
            }
            long cursor=after;
            try(Cursor c=db.rawQuery("SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.id>? AND l.id<=? AND (o.mime IN ('application/pdf','text/plain','application/epub+zip','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.comicbook+zip','application/x-cbz','application/vnd.comicbook-rar','application/x-cbr') OR LOWER(l.display_name) LIKE '%.cbr') ORDER BY l.id LIMIT ?",
                new String[]{Long.toString(after),Long.toString(ceiling),Integer.toString(limit)})){
                while(c.moveToNext()){Item row=item(c);items.add(row);cursor=row.locationId;}
            }
            boolean more=items.size()==limit&&cursor<ceiling;
            return new BookSeedPage(items,more?cursor:ceiling,more);
        }finally{helper.close();}
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
    static String canonicalComicMime(String name,String mime){
        String lower=name==null?"":name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".cbr")&&Set.of("application/octet-stream","application/x-rar-compressed","application/vnd.rar","application/x-cbr").contains(mime))
            return "application/vnd.comicbook-rar";
        if(lower.endsWith(".cbz")&&Set.of("application/octet-stream","application/zip","application/x-cbz").contains(mime))
            return "application/vnd.comicbook+zip";
        return mime;
    }
    private static Item item(Cursor c){
        return new Item(c.getLong(0),c.getString(1),c.getString(2),canonicalComicMime(c.getString(2),c.getString(3)),
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
    public boolean verifyOriginal(Item item)throws IOException{
        if(item==null||!item.sha256.matches("[0-9a-f]{64}"))throw new IOException("Invalid original identity");
        return NexusOriginalStore.matches(new File(new File(privateRoot,"originals"),item.sha256),item.length,item.sha256);
    }
    public NexusOriginalStore.Recovery recoverOriginal(Item item,InputStream replacement)throws IOException{
        if(item==null)throw new IOException("Unknown verified original");
        Item recorded=find(item.sha256);
        if(recorded==null||recorded.length!=item.length)throw new IOException("Original identity no longer recorded");
        return NexusOriginalStore.recoverExact(replacement,privateRoot,recorded.length,recorded.sha256);
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
