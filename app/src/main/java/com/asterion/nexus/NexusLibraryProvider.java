package com.asterion.nexus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, revocable URI grants. No arbitrary paths or original mutation. */
public final class NexusLibraryProvider extends ContentProvider {
    @Override public boolean onCreate(){return getContext()!=null;}
    private NexusRegistry.Item find(Uri uri){
        if(uri==null||uri.getPathSegments().size()!=2)
            throw new IllegalArgumentException("Invalid library URI");
        String kind=uri.getPathSegments().get(0);
        String sha;
        if("original".equals(kind))sha=uri.getLastPathSegment();
        else if("grant".equals(kind))sha=NexusPartnerBridge.grantedSha(getContext(),uri.getLastPathSegment(),android.os.Binder.getCallingUid());
        else throw new IllegalArgumentException("Invalid library URI");
        NexusRegistry.Item item=new NexusRegistry(getContext()).find(sha);
        if(item==null)throw new IllegalArgumentException("Unknown verified original");
        return item;
    }
    @Override public String getType(Uri uri){return find(uri).mime;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        NexusRegistry.Item item=find(uri);
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,"mime_type"}:projection;
        MatrixCursor cursor=new MatrixCursor(columns,1);
        Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++){
            if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=item.displayName;
            else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=item.length;
            else if("mime_type".equals(columns[i]))row[i]=item.mime;
        }
        cursor.addRow(row);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Originals are read-only");
        NexusRegistry.Item item;
        try{item=find(uri);}
        catch(IllegalArgumentException problem){throw new FileNotFoundException(problem.getMessage());}
        try{return ParcelFileDescriptor.open(new NexusRegistry(getContext()).original(item),
                                            ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IOException problem){throw new FileNotFoundException(problem.getMessage());}
    }
    @Override public Uri insert(Uri uri,ContentValues values){
        throw new UnsupportedOperationException("Immutable original");
    }
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){
        throw new UnsupportedOperationException("Immutable original");
    }
    @Override public int delete(Uri uri,String where,String[] args){
        throw new UnsupportedOperationException("Immutable original");
    }
    public static Uri uriFor(android.content.Context context,String contentSha256){
        if(contentSha256==null||!contentSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid original ID");
        return Uri.parse("content://"+context.getPackageName()+".library/original/"+contentSha256);
    }
}
