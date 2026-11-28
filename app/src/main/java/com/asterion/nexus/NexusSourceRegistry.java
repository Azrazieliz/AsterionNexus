package com.asterion.nexus;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit SAF source grants; no access to private folders of donor applications. */
public final class NexusSourceRegistry {
    private static final String PREFERENCES="nexus_sources_v1";
    private static final String KEY="persisted_roots";
    private static final int MAX_SOURCES=32;
    private final Context app;
    public NexusSourceRegistry(Context context){app=context.getApplicationContext();}
    private SharedPreferences prefs(){return app.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);}
    private Set<String> granted(){
        Set<String> active=new LinkedHashSet<>();
        for(UriPermission permission:app.getContentResolver().getPersistedUriPermissions()){
            if(permission.isReadPermission())active.add(permission.getUri().toString());
        }
        return active;
    }
    public synchronized List<Uri> roots(){
        ArrayList<Uri> result=new ArrayList<>();
        Set<String> active=granted();
        try{
            JSONArray saved=new JSONArray(prefs().getString(KEY,"[]"));
            Set<String> unique=new LinkedHashSet<>();
            for(int i=0;i<saved.length()&&i<MAX_SOURCES;i++){
                String entry=saved.optString(i,"");
                if(active.contains(entry)&&unique.add(entry)){
                    Uri uri=Uri.parse(entry);
                    if("content".equals(uri.getScheme())&&uri.getAuthority()!=null)
                        result.add(uri);
                }
            }
        }catch(Exception ignored){}
        return result;
    }
    public synchronized void add(Uri source){
        if(source==null||!"content".equals(source.getScheme())||
           source.getAuthority()==null)throw new IllegalArgumentException("Invalid SAF source URI");
        String id=source.toString();
        if(id.length()>4096||!granted().contains(id))
            throw new SecurityException("Persistent read grant required");
        LinkedHashSet<String> sources=new LinkedHashSet<>();
        for(Uri existing:roots())sources.add(existing.toString());
        if(sources.size()>=MAX_SOURCES&&!sources.contains(id))
            throw new IllegalStateException("Maximum 32 connected trees");
        sources.add(id);
        save(sources);
    }
    public synchronized void remove(Uri source){
        if(source==null)return;
        LinkedHashSet<String> sources=new LinkedHashSet<>();
        for(Uri existing:roots())if(!existing.equals(source))sources.add(existing.toString());
        save(sources);
        try{
            app.getContentResolver().releasePersistableUriPermission(source,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException ignored){}
    }
    private void save(Set<String> values){
        JSONArray array=new JSONArray();
        for(String item:values)array.put(item);
        if(!prefs().edit().putString(KEY,array.toString()).commit())
            throw new IllegalStateException("Cannot persist owner-selected SAF sources");
    }
}
