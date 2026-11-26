package com.asterion.nexus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.openlocal.office.core.FgTeraboxClient;

/** One content-oriented viewer router, regardless of original source. */
public final class NexusFileOpen {
    private NexusFileOpen(){}
    public static void open(Activity activity,Uri uri,String mime,String title){
        if(uri==null)throw new IllegalArgumentException("File location missing");
        String resolved=mime==null||mime.isBlank()?
            FgTeraboxClient.mime(title==null?"":title):mime;
        Intent intent;
        if(resolved.startsWith("image/")){
            intent=new Intent(activity,NexusImageViewerActivity.class);
        }else if(resolved.startsWith("video/")||resolved.startsWith("audio/")){
            intent=new Intent(activity,NexusRounderPlaybackActivity.class);
        }else if(resolved.equals("application/pdf")||resolved.equals("application/epub+zip")||
            resolved.equals("application/zip")||
            resolved.equals("application/vnd.comicbook+zip")||
            resolved.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){
            intent=new Intent(activity,NexusReaderActivity.class);
        }else{
            intent=new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        intent.setDataAndType(uri,resolved);
        intent.putExtra("title",title);
        activity.startActivity(intent);
    }
}
