package com.asterion.videoflow

import android.content.Context
import com.asterion.nexus.NexusRegistry
import java.io.IOException
import java.net.URI

/** Selected public watch page -> actual original resolver -> verified Nexus registry.
 * Never equates a thumbnail, site page, HTTP 200 or manifest preview with an original.
 */
object NexusWebAcquisition {
    @JvmStatic
    @Throws(IOException::class)
    fun acquireSelected(context:Context, selectedUrl:String):NexusRegistry.Item {
        val selected=WebDiscovery.normalize(selectedUrl)
            ?: throw IOException("Selected source URL is invalid")
        val media=if(NativeMediaDownloader.isDirectMediaUrl(selected))
            selected
        else WebDiscovery.directMedia(selected,context)
            ?: throw IOException("No complete original direct file resolved for this selected page")
        val resolved=URI(media)
        if(resolved.scheme!="https" || resolved.userInfo!=null || resolved.host==null)
            throw IOException("Selected source did not resolve to a trusted HTTPS original")
        if(!NativeMediaDownloader.isDirectMediaUrl(media))
            throw IOException("Selected source resolved only to a player/manifest, not an original file")
        return NexusDirectAcquisition.acquire(context,media,selected)
    }
}
