package com.asterion.nexus

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openlocal.office.core.FgTeraboxClient
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class NexusOrganizationJob:JobService(){
    private class Run(val params:JobParameters){
        val stop=AtomicBoolean(false)
        var future:Future<*>?=null
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var active:Run?=null
    override fun onStartJob(params:JobParameters):Boolean{
        active?.let{it.stop.set(true);it.future?.cancel(true)}
        val run=Run(params);active=run
        run.future=worker.submit{
            try{
                for(account in FgTeraboxClient.accounts(applicationContext)){
                    if(run.stop.get()||Thread.currentThread().isInterrupted)break
                    runCatching{NexusAutomaticOrganization.run(applicationContext,account.scope){run.stop.get()||Thread.currentThread().isInterrupted}}
                        .onFailure{android.util.Log.w("NexusOrganization","Cycle unavailable: "+it.javaClass.simpleName)}
                }
            }finally{
                main.post{
                    if(active===run&&!run.stop.get()){active=null;jobFinished(params,false)}
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        active?.takeIf{it.params.jobId==params.jobId}?.let{active=null;it.stop.set(true);it.future?.cancel(true)}
        return true
    }
    override fun onDestroy(){
        active?.let{it.stop.set(true);it.future?.cancel(true)};active=null
        worker.shutdownNow();super.onDestroy()
    }
    companion object{
        private const val JOB=153729
        fun schedule(context:Context){
            val scheduler=context.getSystemService(JobScheduler::class.java)?:return
            if(!NexusAutomaticOrganization.anyEnabled(context)){scheduler.cancel(JOB);return}
            if(scheduler.getPendingJob(JOB)!=null)return
            val result=scheduler.schedule(JobInfo.Builder(JOB,ComponentName(context,NexusOrganizationJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15L*60L*1000L).setPersisted(true).build())
            check(result==JobScheduler.RESULT_SUCCESS){"Automatic organization could not be scheduled"}
        }
    }
}
