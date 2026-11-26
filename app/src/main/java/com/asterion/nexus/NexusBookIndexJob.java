package com.asterion.nexus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resumable, bounded local-only book indexing; never uploads original text. */
public final class NexusBookIndexJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean stop=new AtomicBoolean();
    private Future<?> running;
    @Override public boolean onStartJob(JobParameters params){
        stop.set(false);
        running=worker.submit(()->{
            boolean more=false;
            try{
                NexusBookIndex index=new NexusBookIndex(this);
                more=index.indexNext(stop);
            }catch(Exception ignored){more=true;}
            finally{jobFinished(params,more&&!stop.get());}
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        stop.set(true);
        if(running!=null)running.cancel(true);
        return true;
    }
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
