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
        Database(Context app){super(app,DB_NAME,null,8);setWriteAheadLoggingEnabled(true);}
        @Override public void onConfigure(SQLiteDatabase db){
            super.onConfigure(db);db.setForeignKeyConstraintsEnabled(true);
        }
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE originals(sha256 TEXT PRIMARY KEY,byte_length INTEGER NOT NULL CHECK(byte_length>0),mime TEXT NOT NULL,verified_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE locations(id INTEGER PRIMARY KEY AUTOINCREMENT,sha256 TEXT NOT NULL REFERENCES originals(sha256),source_kind TEXT NOT NULL,account_key TEXT NOT NULL,origin_key TEXT NOT NULL,display_name TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(sha256,source_kind,account_key,origin_key))");
            db.execSQL("CREATE INDEX idx_nexus_library_names ON locations(display_name COLLATE NOCASE)");
            db.execSQL("CREATE INDEX idx_nexus_library_sources ON locations(source_kind,account_key)");
            db.execSQL("CREATE TABLE jobs(job_key TEXT PRIMARY KEY,stage TEXT NOT NULL,received INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,error_code TEXT)");
            createFileRatings(db);createRemoteLocations(db);createCollections(db);createEvictions(db);createPlayback(db);createBookmarks(db);createCloudProofs(db);
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
        private void createCollections(SQLiteDatabase db){
            // Collection definitions remain in Fusion. This extension holds
            // only non-Fusion content membership and SHA-based progress.
            db.execSQL("CREATE TABLE IF NOT EXISTS collection_members(collection_id TEXT NOT NULL,sha256 TEXT NOT NULL REFERENCES originals(sha256),added_at INTEGER NOT NULL,PRIMARY KEY(collection_id,sha256))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_collection_member_sha ON collection_members(sha256,collection_id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS reading_progress(sha256 TEXT PRIMARY KEY REFERENCES originals(sha256),position INTEGER NOT NULL,total INTEGER NOT NULL,updated_at INTEGER NOT NULL,CHECK(position>=0 AND total>0 AND position<total))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_reading_progress_recent ON reading_progress(updated_at DESC)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nexus_rating_score ON file_ratings(score,sha256)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_nexus_locations_sha ON locations(sha256,id)");
        }
        private void createEvictions(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS local_evictions(sha256 TEXT PRIMARY KEY REFERENCES originals(sha256),account TEXT NOT NULL,fs_id INTEGER NOT NULL,remote_md5 TEXT NOT NULL,bytes INTEGER NOT NULL,verified_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','EVICTED')))");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int old,int current){
            if(old<1||current!=8||old>=current)throw new IllegalStateException("Unsupported Nexus registry migration");
            if(old==1)createFileRatings(db);
            if(old<3)createRemoteLocations(db);
            if(old<4)createCollections(db);
            createEvictions(db);createPlayback(db);createBookmarks(db);createCloudProofs(db);
        }
        private void createCloudProofs(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_content_proofs(account TEXT NOT NULL,fs_id INTEGER NOT NULL,sha256 TEXT NOT NULL REFERENCES originals(sha256),bytes INTEGER NOT NULL,md5 TEXT NOT NULL,verified_at INTEGER NOT NULL,PRIMARY KEY(account,fs_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_remote_content_sha ON remote_content_proofs(sha256,verified_at)");
        }
        private void createBookmarks(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS reader_bookmarks(sha256 TEXT PRIMARY KEY REFERENCES originals(sha256),chapter INTEGER NOT NULL CHECK(chapter>=0),fraction REAL NOT NULL CHECK(fraction>=0 AND fraction<=1),updated_at INTEGER NOT NULL,legacy_export TEXT,legacy_key TEXT,UNIQUE(legacy_export,legacy_key))");
        }
        private void createPlayback(SQLiteDatabase db){
            db.execSQL("CREATE TABLE IF NOT EXISTS playback_progress(sha256 TEXT PRIMARY KEY REFERENCES originals(sha256),position_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL,CHECK(position_ms>=0 AND duration_ms>0 AND position_ms<duration_ms))");
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
            else if(filename.endsWith(".xlsx"))type=NexusSpreadsheet.XLSX;
            else if(filename.endsWith(".csv"))type="text/csv";
            else if(filename.endsWith(".obj"))type="model/obj";
            else if(filename.endsWith(".stl"))type="model/stl";
            else if(filename.endsWith(".glb"))type="model/gltf-binary";
            else if(filename.endsWith(".gltf"))type="model/gltf+json";
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
                if(new File(new File(privateRoot,"originals"),bytes.sha256).isFile())
                    db.delete("local_evictions","sha256=?",new String[]{bytes.sha256});
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
            "SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.source_kind IN ('downloader-direct','nexus-comic','nexus-novel','nexus-assembly','nexus-video-edit','nexus-derivative','nexus-translation') ORDER BY l.id LIMIT ? OFFSET ?",
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
    /** Called only after a complete remote stream is independently SHA-256
     * and MD5 verified against a freshly revalidated scoped object. No bytes
     * are fabricated or removed; the original is registered as cloud-only. */
    Item registerVerifiedRemote(String sha,String account,long fsId,String path,long bytes,String md5,String mime,String displayName)throws IOException{
        requireRatingIdentity(sha);remoteIdentity(account,fsId,path,bytes,md5);
        if(!md5.matches("[0-9a-f]{32}"))throw new IOException("Verified cloud checksum required");
        String type=canonicalComicMime(displayName,label(mime,"application/octet-stream").toLowerCase(Locale.ROOT));
        if(!type.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))type="application/octet-stream";
        Database helper=new Database(app);
        SQLiteDatabase db=helper.getWritableDatabase();
        long now=System.currentTimeMillis();
        db.beginTransaction();
        try{
            try(Cursor c=db.rawQuery("SELECT byte_length FROM originals WHERE sha256=?",new String[]{sha})){
                if(c.moveToFirst()&&c.getLong(0)!=bytes)throw new IOException("Canonical length conflict");
            }
            boolean pinned=false;
            try(Cursor c=db.rawQuery("SELECT sha256,bytes,remote_md5,pinned FROM remote_locations WHERE account=? AND fs_id=?",new String[]{account,Long.toString(fsId)})){
                if(c.moveToFirst()){
                    if(!sha.equals(c.getString(0))||c.getLong(1)!=bytes||(!c.getString(2).isEmpty()&&!md5.equals(c.getString(2))))
                        throw new IOException("Cloud identity conflicts with an existing binding");
                    pinned=c.getInt(3)!=0;
                }
            }
            ContentValues original=new ContentValues();
            original.put("sha256",sha);original.put("byte_length",bytes);original.put("mime",type);original.put("verified_at",now);
            db.insertWithOnConflict("originals",null,original,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues location=new ContentValues();
            location.put("sha256",sha);location.put("source_kind","cloud-verified");location.put("account_key",account);
            location.put("origin_key",NexusOriginalStore.sourceKey("terabox:"+account+":"+fsId));
            location.put("display_name",label(displayName,sha));location.put("created_at",now);
            db.insertWithOnConflict("locations",null,location,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues remote=new ContentValues();
            remote.put("account",account);remote.put("fs_id",fsId);remote.put("path",path);remote.put("bytes",bytes);
            remote.put("remote_md5",md5);remote.put("sha256",sha);remote.put("pinned",pinned?1:0);remote.put("verified_at",now);
            if(db.insertWithOnConflict("remote_locations",null,remote,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IOException("Cloud location not stored");
            ContentValues proof=new ContentValues();
            proof.put("account",account);proof.put("fs_id",fsId);proof.put("sha256",sha);proof.put("bytes",bytes);proof.put("md5",md5);proof.put("verified_at",now);
            if(db.insertWithOnConflict("remote_content_proofs",null,proof,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IOException("Cloud proof not stored");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();helper.close();}
        Item item=find(sha);
        if(item==null)throw new IOException("Cloud original not indexed");
        return item;
    }
    public List<RemoteLocation> verifiedCloudLocations(String sha){
        requireRatingIdentity(sha);
        ArrayList<RemoteLocation> rows=new ArrayList<>();
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT r.account,r.fs_id,r.path,r.bytes,r.remote_md5,r.sha256,r.pinned,r.verified_at FROM remote_locations r JOIN remote_content_proofs p ON p.account=r.account AND p.fs_id=r.fs_id AND p.sha256=r.sha256 AND p.bytes=r.bytes AND p.md5=r.remote_md5 WHERE r.sha256=? ORDER BY p.verified_at DESC LIMIT 100",new String[]{sha})){
            while(c.moveToNext())rows.add(new RemoteLocation(c));
        }finally{helper.close();}
        return rows;
    }
    public void bindRemote(Item item,String account,long fsId,String path,long bytes,String md5,boolean pinned)throws IOException{
        remoteIdentity(account,fsId,path,bytes,md5);
        if(item==null||item.length!=bytes)throw new IOException("Verified content required for remote binding");
        Database helper=new Database(app);
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            if(!verifyOriginal(item))throw new IOException("Verified content required for remote binding");
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

    public boolean localPresent(Item item){
        if(item==null||!item.sha256.matches("[0-9a-f]{64}"))return false;
        return java.nio.file.Files.exists(new File(new File(privateRoot,"originals"),item.sha256).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    private boolean eligibleForEviction(SQLiteDatabase db,String sha){
        try(Cursor c=db.rawQuery("SELECT 1 FROM locations WHERE sha256=? AND source_kind NOT IN ('downloader-direct','nexus-comic','nexus-novel','nexus-assembly','nexus-video-edit','nexus-derivative','nexus-translation') LIMIT 1",new String[]{sha})){
            if(c.moveToFirst())return false;
        }
        try(Cursor c=db.rawQuery("SELECT 1 FROM remote_locations WHERE sha256=? AND pinned=1 LIMIT 1",new String[]{sha})){
            return !c.moveToFirst();
        }
    }
    public List<RemoteLocation> cleanupCandidates(String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);ArrayList<RemoteLocation> rows=new ArrayList<>();
        try{
            SQLiteDatabase db=helper.getReadableDatabase();
            if(!eligibleForEviction(db,sha))return rows;
            try(Cursor c=db.rawQuery("SELECT account,fs_id,path,bytes,remote_md5,sha256,pinned,verified_at FROM remote_locations WHERE sha256=? AND pinned=0 AND length(remote_md5)=32 ORDER BY verified_at DESC LIMIT 20",new String[]{sha})){
                while(c.moveToNext())rows.add(new RemoteLocation(c));
            }
        }finally{helper.close();}
        return rows;
    }
    public RemoteLocation evictedLocation(Item item){
        if(item==null||localPresent(item))return null;
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT r.account,r.fs_id,r.path,r.bytes,r.remote_md5,r.sha256,r.pinned,r.verified_at FROM local_evictions e JOIN remote_locations r ON r.account=e.account AND r.fs_id=e.fs_id AND r.sha256=e.sha256 AND r.bytes=e.bytes AND r.remote_md5=e.remote_md5 WHERE e.sha256=? AND e.state IN ('PREPARED','EVICTED')",
            new String[]{item.sha256})){
            return c.moveToFirst()?new RemoteLocation(c):null;
        }finally{helper.close();}
    }
    private void checkEviction(SQLiteDatabase db,Item item,RemoteLocation remote)throws IOException{
        if(!eligibleForEviction(db,item.sha256))throw new IOException("Pinned or non-acquisition source must be retained");
        try(Cursor c=db.rawQuery("SELECT bytes,remote_md5,path FROM remote_locations WHERE account=? AND fs_id=? AND sha256=? AND pinned=0",new String[]{remote.account,Long.toString(remote.fsId),item.sha256})){
            if(!c.moveToFirst()||c.getLong(0)!=item.length||!remote.md5.equals(c.getString(1))||!remote.path.equals(c.getString(2)))
                throw new IOException("Remote binding changed");
        }
    }
    /** The caller independently read back the complete remote SHA-256.
     * Prepare is committed before unlink so crash recovery can reopen the
     * known surviving remote identity. No physical donor file is touched. */
    void evictAfterReadback(Item item,RemoteLocation remote,long verifiedAt)throws IOException{
        if(item==null||remote==null||!item.sha256.equals(remote.sha256)||remote.length!=item.length||
            !remote.md5.matches("[0-9a-f]{32}")||verifiedAt<=0||Math.abs(System.currentTimeMillis()-verifiedAt)>300000L)
            throw new IOException("Fresh complete cloud readback required");
        Database helper=new Database(app);
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            db.beginTransaction();
            try{
                checkEviction(db,item,remote);
                if(!verifyOriginal(item))throw new IOException("Local bytes failed verification");
                ContentValues row=new ContentValues();
                row.put("sha256",item.sha256);row.put("account",remote.account);row.put("fs_id",remote.fsId);
                row.put("remote_md5",remote.md5);row.put("bytes",item.length);row.put("verified_at",verifiedAt);row.put("state","PREPARED");
                if(db.insertWithOnConflict("local_evictions",null,row,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IOException("Cleanup journal failed");
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            db.beginTransaction();
            try{
                checkEviction(db,item,remote);
                if(!NexusOriginalStore.evictVerified(privateRoot,item.length,item.sha256))throw new IOException("Local original disappeared");
                db.execSQL("UPDATE local_evictions SET state='EVICTED' WHERE sha256=? AND account=? AND fs_id=?",new Object[]{item.sha256,remote.account,remote.fsId});
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }finally{helper.close();}
    }
    public void addCollectionMember(String collection,String sha){
        requireRatingIdentity(sha);
        if(collection==null||collection.isBlank()||collection.length()>256)throw new IllegalArgumentException("Invalid collection");
        Database helper=new Database(app);
        try{
            ContentValues row=new ContentValues();
            row.put("collection_id",collection);row.put("sha256",sha);row.put("added_at",System.currentTimeMillis());
            helper.getWritableDatabase().insertWithOnConflict("collection_members",null,row,SQLiteDatabase.CONFLICT_IGNORE);
        }finally{helper.close();}
    }
    public void removeCollectionMember(String collection,String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);
        try{helper.getWritableDatabase().delete("collection_members","collection_id=? AND sha256=?",new String[]{collection,sha});}
        finally{helper.close();}
    }
    public void notePlaybackProgress(String sha,long position,long duration){
        requireRatingIdentity(sha);
        if(duration<=0||position<0||position>=duration)throw new IllegalArgumentException("Invalid playback progress");
        Database helper=new Database(app);
        try{
            ContentValues row=new ContentValues();row.put("sha256",sha);row.put("position_ms",position);row.put("duration_ms",duration);row.put("updated_at",System.currentTimeMillis());
            helper.getWritableDatabase().insertWithOnConflict("playback_progress",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        }finally{helper.close();}
    }
    public long playbackPosition(String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT position_ms FROM playback_progress WHERE sha256=?",new String[]{sha})){
            return c.moveToFirst()?c.getLong(0):-1L;
        }finally{helper.close();}
    }
    public static final class Bookmark{
        public final int chapter;public final double fraction;
        Bookmark(int chapter,double fraction){this.chapter=chapter;this.fraction=fraction;}
    }
    public Bookmark bookmark(String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT chapter,fraction FROM reader_bookmarks WHERE sha256=?",new String[]{sha})){
            return c.moveToFirst()?new Bookmark(c.getInt(0),c.getDouble(1)):null;
        }finally{helper.close();}
    }
    private static void validateBookmark(int chapter,double fraction){
        if(chapter<0||chapter>1000000||!Double.isFinite(fraction)||fraction<0||fraction>1)
            throw new IllegalArgumentException("Invalid reader bookmark");
    }
    public void saveBookmark(String sha,int chapter,double fraction){
        requireRatingIdentity(sha);validateBookmark(chapter,fraction);
        Database helper=new Database(app);
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            ContentValues row=new ContentValues();
            row.put("chapter",chapter);row.put("fraction",fraction);row.put("updated_at",System.currentTimeMillis());
            db.beginTransaction();
            try{
                if(db.update("reader_bookmarks",row,"sha256=?",new String[]{sha})==0){
                    row.put("sha256",sha);db.insertOrThrow("reader_bookmarks",null,row);
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }finally{helper.close();}
    }
    public void bindRounderBookmark(String exportSha,String key,String sha,int chapter,double fraction){
        requireRatingIdentity(exportSha);requireRatingIdentity(sha);validateBookmark(chapter,fraction);
        if(key==null||key.length()>1024||!key.startsWith("fg.book.preferences.v2.")||!key.endsWith(".position"))
            throw new IllegalArgumentException("Invalid legacy bookmark key");
        Database helper=new Database(app);
        try{
            ContentValues row=new ContentValues();
            row.put("sha256",sha);row.put("chapter",chapter);row.put("fraction",fraction);
            row.put("legacy_export",exportSha);row.put("legacy_key",key);row.put("updated_at",System.currentTimeMillis());
            // No overwrite of an existing bookmark or previously bound legacy key.
            helper.getWritableDatabase().insertOrThrow("reader_bookmarks",null,row);
        }finally{helper.close();}
    }
    public String rounderBookmarkBinding(String exportSha,String key){
        requireRatingIdentity(exportSha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT sha256 FROM reader_bookmarks WHERE legacy_export=? AND legacy_key=?",new String[]{exportSha,key})){
            return c.moveToFirst()?c.getString(0):null;
        }finally{helper.close();}
    }
    public boolean hasRounderBindings(String exportSha){
        requireRatingIdentity(exportSha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT 1 FROM reader_bookmarks WHERE legacy_export=? LIMIT 1",new String[]{exportSha})){
            return c.moveToFirst();
        }finally{helper.close();}
    }
    public void noteReadingProgress(String sha,int position,int total){
        requireRatingIdentity(sha);
        if(position<0||total<1||position>=total)throw new IllegalArgumentException("Invalid reading progress");
        Database helper=new Database(app);
        try{
            ContentValues row=new ContentValues();
            row.put("sha256",sha);row.put("position",position);row.put("total",total);row.put("updated_at",System.currentTimeMillis());
            helper.getWritableDatabase().insertWithOnConflict("reading_progress",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        }finally{helper.close();}
    }
    public int readingPosition(String sha){
        requireRatingIdentity(sha);
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT position FROM reading_progress WHERE sha256=?",new String[]{sha})){
            return c.moveToFirst()?c.getInt(0):-1;
        }finally{helper.close();}
    }
    /** Membership is computed on demand over canonical SHA identities.
     * Each result is one content item irrespective of multiple proven locations. */
    public List<Item> collectionPage(String kind,String value,long after,int limit){
        if(after<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid collection page");
        String filter;
        ArrayList<String> args=new ArrayList<>();
        args.add(Long.toString(after));
        switch(kind){
            case "manual":
                filter="EXISTS(SELECT 1 FROM collection_members m WHERE m.sha256=o.sha256 AND m.collection_id=?)";args.add(value);break;
            case "local_rating":
                int score=Integer.parseInt(value);
                if(score<0||score>100)throw new IllegalArgumentException("Invalid rating");
                filter="EXISTS(SELECT 1 FROM file_ratings r WHERE r.sha256=o.sha256 AND r.score>=?)";args.add(value);break;
            case "offline_pin":
                filter="EXISTS(SELECT 1 FROM remote_locations r WHERE r.sha256=o.sha256 AND r.pinned=1)";break;
            case "continue_watching":
                filter="o.mime LIKE 'video/%' AND EXISTS(SELECT 1 FROM playback_progress p WHERE p.sha256=o.sha256 AND p.position_ms>0)";break;
            case "continue_reading":
                filter="EXISTS(SELECT 1 FROM reading_progress r WHERE r.sha256=o.sha256 AND r.position<r.total-1)";break;
            case "related_output":
                filter="EXISTS(SELECT 1 FROM content_relations r WHERE r.output_sha=o.sha256)";break;
            case "media_type":
                switch(value){
                    case "image":filter="o.mime LIKE 'image/%'";break;
                    case "video":filter="o.mime LIKE 'video/%'";break;
                    case "audio":filter="o.mime LIKE 'audio/%'";break;
                    case "books":filter="(o.mime IN ('application/pdf','text/plain','application/epub+zip','application/x-cbz','application/x-cbr') OR o.mime LIKE '%comic%' OR o.mime LIKE '%wordprocessingml%')";break;
                    case "assets":filter="(o.mime NOT LIKE 'image/%' AND o.mime NOT LIKE 'video/%' AND o.mime NOT LIKE 'audio/%' AND o.mime NOT IN ('application/pdf','text/plain','application/epub+zip','application/x-cbz','application/x-cbr') AND o.mime NOT LIKE '%comic%' AND o.mime NOT LIKE '%wordprocessingml%')";break;
                    default:throw new IllegalArgumentException("Invalid media rule");
                }break;
            default:throw new IllegalArgumentException("Unsupported content collection");
        }
        args.add(Integer.toString(limit));
        ArrayList<Item> rows=new ArrayList<>();
        Database helper=new Database(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON o.sha256=l.sha256 WHERE l.id>? AND l.id=(SELECT MIN(other.id) FROM locations other WHERE other.sha256=l.sha256) AND "+filter+" ORDER BY l.id LIMIT ?",
            args.toArray(new String[0]))){
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
            try(Cursor c=db.rawQuery("SELECT l.id,l.sha256,l.display_name,o.mime,l.source_kind,l.account_key,o.byte_length FROM locations l JOIN originals o ON l.sha256=o.sha256 WHERE l.id>? AND l.id<=? AND l.source_kind<>'cloud-verified' AND (o.mime IN ('application/pdf','text/plain','application/epub+zip','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.comicbook+zip','application/x-cbz','application/vnd.comicbook-rar','application/x-cbr') OR LOWER(l.display_name) LIKE '%.cbr') ORDER BY l.id LIMIT ?",
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
        if(!java.nio.file.Files.isRegularFile(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)||file.length()!=item.length)
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
        NexusOriginalStore.Recovery result=NexusOriginalStore.recoverExact(replacement,privateRoot,recorded.length,recorded.sha256);
        Database helper=new Database(app);
        try{if(localPresent(recorded))helper.getWritableDatabase().delete("local_evictions","sha256=?",new String[]{recorded.sha256});}finally{helper.close();}
        return result;
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
