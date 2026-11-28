package com.asterion.nexus

import com.azrael.pixivdumpsync.*
import java.net.URI
import java.util.Locale

/** Dispatches existing provider adapters rather than introducing a second acquisition engine. */
internal object NexusProviderDispatch {
    enum class Route { ALREADY_TRACKED,TORRENT,GALLERY,BOOK_SERIES,COMIC_SERIES,NATIVE_VIDEO,WEB_VIDEO,PUBLIC_FILE,AUTHORIZED_ITEM }
    fun route(url:String,area:String,kind:String,filename:String?,queued:Boolean):Route {
        if(queued)return Route.ALREADY_TRACKED
        if(url.startsWith("magnet:?",true))return Route.TORRENT
        val uri=URI(url)
        require(uri.scheme.equals("https",true)&&uri.userInfo==null&&uri.host!=null){"Invalid selected HTTPS source"}
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        if(path.endsWith(".torrent"))return Route.TORRENT
        if(kind.equals("torrent",true)||filename.orEmpty().endsWith(".torrent",true)){
            // A result without a concrete descriptor is not an automatic transfer.
            // The donor's metadata resolver opens the actual selection flow.
            return Route.TORRENT
        }
        if(area=="COSPLAY")return Route.GALLERY
        if(area=="BOOKS"&&NovelEpubParser.supportedSeries(url))return Route.BOOK_SERIES
        if(area=="COMICS"&&!TeraBoxRemoteOriginals.verifiedPublicFileLink(url))return Route.COMIC_SERIES
        if(SelectedVideoRouter.isSelectedVideo(area,url,kind))return Route.NATIVE_VIDEO
        if(kind=="video"||area=="VIDEOS")return Route.WEB_VIDEO
        if(TeraBoxRemoteOriginals.verifiedPublicFileLink(url))return Route.PUBLIC_FILE
        require(area in setOf("ARTWORK","BOOKS","COMICS","GAMES","ASSETS","CREATORS","X","KOIKATSU","PIXIV","GALLERY","VIDEO","VIDEOS")){"No supported acquisition category"}
        return Route.AUTHORIZED_ITEM
    }
}
