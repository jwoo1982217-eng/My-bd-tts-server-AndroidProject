package com.github.jing332.tts_server_android.constant

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.BuildConfig
import com.github.jing332.tts_server_android.app
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.Locale

@SuppressLint("SimpleDateFormat")
@Suppress("DEPRECATION")
object AppConst {

    val fileProviderAuthor = BuildConfig.APPLICATION_ID + ".fileprovider"
    val localBroadcast by lazy { LocalBroadcastManager.getInstance(App.context) }
    val externalFilesDir by lazy { checkNotNull(App.context.getExternalFilesDir("")) { "getExternalFilesDir() == null" } }
    val externalCacheDir by lazy { checkNotNull(App.context.externalCacheDir) { "externalCacheDir == null" } }


    var isSysTtsLogEnabled = true
    var isServerLogEnabled = false

    @OptIn(ExperimentalSerializationApi::class)
    val jsonBuilder by lazy {
        Json {
            allowStructuredMapKeys = true
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            explicitNulls = false //忽略为null的字段
            allowStructuredMapKeys = true
        }
    }

    val isCnLocale: Boolean
        get() = App.context.resources.configuration.locale.language.endsWith("zh")

    val locale: Locale
        get() = App.context.resources.configuration.locale

    val localeCode: String
        get() = locale.run { "$language-$country" }

    val appInfo: AppInfo by lazy {
        val appInfo = AppInfo()
        App.context.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_ACTIVITIES
        )
            ?.let {
                appInfo.versionName = it.versionName ?: ""
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    appInfo.versionCode = it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    appInfo.versionCode = it.versionCode.toLong()
                }
            }
        appInfo
    }

    data class AppInfo(
        var versionCode: Long = 0L,
        var versionName: String = "",
    )
}