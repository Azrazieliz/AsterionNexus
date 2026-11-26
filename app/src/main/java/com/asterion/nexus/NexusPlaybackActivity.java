package com.asterion.nexus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

public final class NexusPlaybackActivity extends Activity {
    private VideoView video;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        video=new VideoView(this);
        root.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        setContentView(root);
        MediaController controls=new MediaController(this);
        controls.setAnchorView(video);video.setMediaController(controls);
        if(getIntent().getData()==null){finish();return;}
        video.setVideoURI(getIntent().getData());
        video.setOnPreparedListener(player->{player.setLooping(false);video.start();});
        video.setOnErrorListener((player,what,extra)->{
            android.widget.Toast.makeText(this,"Playback failed: "+what,
                android.widget.Toast.LENGTH_LONG).show();return false;
        });
        video.requestFocus();
    }
    @Override protected void onPause(){if(video!=null)video.pause();super.onPause();}
    @Override protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
