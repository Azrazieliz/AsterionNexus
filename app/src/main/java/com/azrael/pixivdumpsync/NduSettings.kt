package com.azrael.pixivdumpsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An NDUS value grants access to the entire cloud account. Never log or export it. */
object NduSettings {
    private const val PREF = "nocturne_ndus_cloud"
    private const val ALIAS = "nocturne-ndus-aes-gcm-v1"
    private const val SECRET = "ndus_cipher"
    private const val ENABLE = "enabled"
    private const val KUROHA_DIR = "kuroha_dir"
    private const val KYORA_DIR = "kyora_dir"
    private const val DOMAIN = "domain"
    private const val ACCOUNT_SCOPE = "account_scope"
    private const val ACCOUNT_VAULT = "nexus_upload_account_vault"

    /** Opaque account binding, not a credential-derived identifier. */
    fun currentAccountScope(ctx:Context):String=ctx.getSharedPreferences(PREF,0).getString(ACCOUNT_SCOPE,"").orEmpty()
    @Synchronized fun accountScope(ctx:Context):String {
        if(cookie(ctx)==null)return ""
        val old=currentAccountScope(ctx)
        if(old.isNotBlank())return old
        val scope=java.util.UUID.randomUUID().toString()
        check(ctx.getSharedPreferences(PREF,0).edit().putString(ACCOUNT_SCOPE,scope).commit()){"Account scope not saved"}
        return scope
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREF,0).getBoolean(ENABLE,false)
    private const val CLOUD_ONLY = "cloud_only"
    /** TEMPORARY ACCEPTANCE MODE: downloads go to phone internal/shared
     * Downloads until the owner re-enables direct-to-cloud.
     * Existing cloud reservations and encrypted NDUS are preserved.
     */
    fun cloudOnly(ctx:Context):Boolean =
        ctx.getSharedPreferences(PREF,0).getBoolean(CLOUD_ONLY,false)
    fun setCloudOnly(ctx:Context,cloud:Boolean){
        ctx.getSharedPreferences(PREF,0).edit().putBoolean(CLOUD_ONLY,cloud).apply()
        // Paused cloud receipts are retained in SQLite, never discarded.
        NduCloudScheduler.configure(ctx,enabled(ctx))
    }
    fun setEnabled(ctx: Context, yes: Boolean) {
        ctx.getSharedPreferences(PREF,0).edit().putBoolean(ENABLE,yes).apply()
        NduCloudScheduler.configure(ctx,yes)
    }
    fun domain(ctx: Context) = ctx.getSharedPreferences(PREF,0)
        .getString(DOMAIN,"www.terabox.com") ?: "www.terabox.com"
    fun setDomain(ctx: Context, host: String) {
        require(host in allowedDomains) { "Unsupported TeraBox domain" }
        ctx.getSharedPreferences(PREF,0).edit().putString(DOMAIN,host).apply()
    }
    private val allowedDomains = setOf("www.terabox.com","nephobox.com")

    fun destination(ctx: Context, category: String): String {
        require(category in setOf("kuroha","kyora","x","comics","videos",
            "books","games","assets","koikatsu","images","torrents","creators","originals"))
        val key = when(category) {
            "kuroha"->KUROHA_DIR;"kyora"->KYORA_DIR
            else->category+"_dir"
        }
        val folder = when(category) {
            "kuroha"->"Kuroha";"kyora"->"Kyora"
            "x"->"X";"comics"->"Comics"
            "videos"->"Videos";"books"->"Books";"games"->"Games"
            "assets"->"Assets";"koikatsu"->"Koikatsu"
            "images"->"Images";"torrents"->"Torrents"
            "creators"->"Creators"
            else->"Originals"
        }
        val fallback="/Nocturne/$folder"
        return ctx.getSharedPreferences(PREF,0).getString(key,fallback) ?: fallback
    }
    fun setDestination(ctx: Context, category: String, directory: String) {
        require(category in setOf("kuroha","kyora"))
        val path = validateDirectory(directory)
        ctx.getSharedPreferences(PREF,0).edit()
            .putString(when(category){
                "kuroha"->KUROHA_DIR;"kyora"->KYORA_DIR
                else->category+"_dir"
            },path).apply()
    }
    fun validateDirectory(input: String): String {
        val path = input.trim().trimEnd('/')
        require(path.length in 2..180 && path.startsWith("/") &&
            !path.contains("..") && !path.contains('\\') &&
            !path.contains('?') && !path.contains('#') &&
            !path.contains('\n') && !path.contains('\r') &&
            path.split('/').drop(1).all { it.isNotBlank() && it != "." }) {
            "Use a cloud folder like /Nocturne/Kyora"
        }
        return path
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
    }
    internal class Session(val token:String,val scope:String)
    @Synchronized internal fun session(ctx:Context):Session?{
        val token=cookie(ctx)?:return null
        val scope=accountScope(ctx)
        return if(scope.isBlank())null else Session(token,scope)
    }
    @Synchronized fun save(ctx: Context, input: String) {
        val cookie = parseNdus(input)
        val previous=this.cookie(ctx)
        val scope=if(previous==cookie)accountScope(ctx) else java.util.UUID.randomUUID().toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val a=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)
        val b=Base64.encodeToString(cipher.doFinal(cookie.toByteArray()),Base64.NO_WRAP)
        check(ctx.getSharedPreferences(PREF,0).edit().putString(SECRET,"$a:$b")
            .putString(ACCOUNT_SCOPE,scope).commit()){"Encrypted session not saved"}
    }
    /** Persist encrypted upload sessions separately from the active donor slot.
     * The browser scope is an opaque local account namespace, never a token.
     */
    @Synchronized internal fun saveAccount(ctx:Context,browserScope:String,input:String,uploadScope:String=browserScope){
        require(browserScope.matches(Regex("[0-9a-f-]{36}"))&&uploadScope.matches(Regex("[0-9a-f-]{36}")))
        val token=parseNdus(input)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val encoded=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+
            Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
        check(ctx.getSharedPreferences(ACCOUNT_VAULT,0).edit()
            .putString(browserScope+":cipher",encoded).putString(browserScope+":scope",uploadScope)
            .putString(browserScope+":domain",domain(ctx)).commit()){"Account upload session not saved"}
    }
    /** Encrypted, immutable account lease for a queued upload. Selection of
     * another destination never changes credentials used by an existing job. */
    internal class UploadAccount(val browserScope:String,val scope:String,val token:String,val host:String,internal val cipherIdentity:String)
    @Synchronized internal fun uploadAccount(ctx:Context,browserScope:String):UploadAccount?{
        val vault=ctx.getSharedPreferences(ACCOUNT_VAULT,0)
        val encoded=vault.getString(browserScope+":cipher",null)?:return null
        val scope=vault.getString(browserScope+":scope",null)?:return null
        val host=vault.getString(browserScope+":domain",null)?:return null
        if(!browserScope.matches(Regex("[0-9a-f-]{36}"))||!scope.matches(Regex("[0-9a-f-]{36}"))||host !in allowedDomains)return null
        val token=runCatching{
            val parts=encoded.split(':',limit=2)
            require(parts.size==2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
        }.getOrNull()?:return null
        return UploadAccount(browserScope,scope,token,host,encoded)
    }
    @Synchronized internal fun uploadAccounts(ctx:Context):List<UploadAccount>{
        val authorized=com.openlocal.office.core.FgTeraboxClient.accounts(ctx).map{it.scope}.toSet()
        val accounts=authorized.mapNotNull{uploadAccount(ctx,it)}
        // An ambiguous legacy mapping is held, never assigned by ordering.
        return accounts.groupBy{it.scope}.values.filter{it.size==1}.map{it.single()}
    }
    @Synchronized internal fun stillAuthorized(ctx:Context,account:UploadAccount):Boolean{
        val vault=ctx.getSharedPreferences(ACCOUNT_VAULT,0)
        return vault.getString(account.browserScope+":scope",null)==account.scope&&
            vault.getString(account.browserScope+":domain",null)==account.host&&
            vault.getString(account.browserScope+":cipher",null)==account.cipherIdentity
    }
    internal fun hasAccount(ctx:Context,browserScope:String):Boolean=
        ctx.getSharedPreferences(ACCOUNT_VAULT,0).contains(browserScope+":cipher")
    @Synchronized internal fun activateAccount(ctx:Context,browserScope:String):String{
        val vault=ctx.getSharedPreferences(ACCOUNT_VAULT,0)
        val cipher=vault.getString(browserScope+":cipher",null)?:error("Account upload session unavailable")
        val scope=vault.getString(browserScope+":scope",null)?:error("Account identity unavailable")
        val host=vault.getString(browserScope+":domain","www.terabox.com")?:error("Account host unavailable")
        require(host in allowedDomains)
        check(ctx.getSharedPreferences(PREF,0).edit().putString(SECRET,cipher)
            .putString(ACCOUNT_SCOPE,scope).putString(DOMAIN,host).commit()){"Upload account selection failed"}
        return scope
    }
    fun parseNdus(input: String): String {
        val cleaned=input.trim()
        val value = if (cleaned.contains(';') || cleaned.startsWith("ndus=",true)) {
            val item=cleaned.removePrefix("Cookie:").split(';').firstOrNull {
                it.trim().startsWith("ndus=",ignoreCase=true)
            } ?: throw IllegalArgumentException("No ndus value found")
            item.substringAfter('=').trim()
        } else cleaned
        require(value.length in 8..4096 && !value.contains('\r') &&
            !value.contains('\n') && !value.contains(';') &&
            !value.contains(' ')) { "Invalid NDUS cookie" }
        return value
    }
    fun cookie(ctx: Context): String? {
        val encoded=ctx.getSharedPreferences(PREF,0).getString(SECRET,null) ?: return null
        return runCatching {
            val parts=encoded.split(':',limit=2)
            require(parts.size==2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),
                GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)))
        }.getOrNull()
    }
    @Synchronized fun clear(ctx: Context) {
        NduCloudScheduler.configure(ctx,false)
        ctx.getSharedPreferences(PREF,0).edit().remove(SECRET).remove(ACCOUNT_SCOPE)
            .putBoolean(ENABLE,false).apply()
    }
    fun configured(ctx: Context) = enabled(ctx) && cookie(ctx)!=null
}
