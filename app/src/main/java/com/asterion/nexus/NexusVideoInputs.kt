package com.asterion.nexus

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Bounded session staging for verified cloud-only media; never pins or deletes originals. */
object NexusVideoInputs{
    const val LIMIT=8L*1024*1024*1024
    private var leases=0
    private fun dir(context:Context)=File(context.cacheDir,"nexus-video-inputs")
    @Synchronized fun begin(context:Context){if(leases==0)dir(context).deleteRecursively();leases++}
    @Synchronized fun end(context:Context){if(leases>0)leases--;if(leases==0)dir(context).deleteRecursively()}
    @JvmStatic @Synchronized fun file(context:Context,item:NexusRegistry.Item):File{
        val registry=NexusRegistry(context)
        if(registry.localPresent(item)){
            check(registry.verifyOriginal(item)){"Local original failed verification"}
            return registry.original(item)
        }
        check(leases>0){"Cloud media requires an active editor session"}
        require(item.length in 1..LIMIT&&item.sha256.matches(Regex("[0-9a-f]{64}"))){"Cloud video exceeds temporary staging limit"}
        val folder=dir(context);check(folder.isDirectory||folder.mkdirs()){"Temporary staging unavailable"}
        val target=File(folder,item.sha256+".media")
        if(target.exists()){
            if(NexusOriginalStore.matches(target,item.length,item.sha256))return target
            check(target.delete()){"Invalid temporary file could not be removed"}
        }
        val used=folder.listFiles().orEmpty().filter{it.isFile}.sumOf{it.length()}
        check(item.length<=LIMIT-used&&folder.usableSpace>=item.length+256L*1024*1024){"Insufficient temporary storage for selected cloud videos (8 GiB session limit)"}
        val part=File.createTempFile("source-",".part",folder)
        try{
            ParcelFileDescriptor.AutoCloseInputStream(NexusCloudRetention.openEvicted(context,item)).use{input->
                part.outputStream().use{output->
                    val bytes=ByteArray(262144);var count=0L
                    while(true){
                        if(Thread.currentThread().isInterrupted)throw InterruptedIOException("Staging cancelled")
                        val n=input.read(bytes);if(n<0)break;if(n==0)continue
                        if(n>item.length-count)throw IOException("Cloud stream exceeded expected size")
                        output.write(bytes,0,n);count+=n
                    }
                    check(count==item.length){"Incomplete cloud input"}
                }
            }
            check(NexusOriginalStore.matches(part,item.length,item.sha256)){"Cloud input hash mismatch"}
            check(part.renameTo(target)){"Temporary publication failed"}
            return target
        }finally{part.delete()}
    }
}
