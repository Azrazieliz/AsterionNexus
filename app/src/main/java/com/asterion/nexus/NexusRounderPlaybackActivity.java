package com.asterion.nexus;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import com.openlocal.office.core.FgMediaPlayer;

/** One actual Media3 Rounder-backed player for native and remote seekable media. */
public final class NexusRounderPlaybackActivity extends Activity {
    private FgMediaPlayer player;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Uri uri=getIntent().getData();
        if(uri==null){finish();return;}
        String label=getIntent().getStringExtra("title");
        boolean video=getIntent().getType()==null||!getIntent().getType().startsWith("audio/");
        player=new FgMediaPlayer(this,uri,label==null?"Media":label,
            video,()->finish(),null);
        long position=getIntent().getLongExtra("nexus_position_ms",0L);
        if(position>0)player.seekToPosition(position);
        setContentView(player.getView());
    }
    @Override public void onDestroy(){
        if(player!=null)player.close();
        super.onDestroy();
    }
}
