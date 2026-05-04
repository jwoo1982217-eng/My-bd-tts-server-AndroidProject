package com.github.jing332.tts_server_android.service.systts

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat.stopForeground
import androidx.core.content.ContextCompat
import com.github.jing332.common.utils.StringUtils
import com.github.jing332.common.utils.limitLength
import com.github.jing332.common.utils.longToast
import com.github.jing332.common.utils.registerGlobalReceiver
import com.github.jing332.common.utils.runOnUI
import com.github.jing332.common.utils.sizeToReadable
import com.github.jing332.common.utils.startForegroundCompat
import com.github.jing332.common.utils.toHtmlBold
import com.github.jing332.common.utils.toHtmlSmall
import com.github.jing332.database.dbm
import com.github.jing332.database.entities.systts.AudioParams
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.database.entities.systts.TtsConfigurationDTO
import com.github.jing332.tts.ConfigType
import com.github.jing332.tts.MixSynthesizer
import com.github.jing332.tts.SynthesizerConfig
import com.github.jing332.tts.error.StreamProcessorError
import com.github.jing332.tts.error.SynthesisError
import com.github.jing332.tts.error.TextProcessorError
import com.github.jing332.tts.synthesizer.RequestPayload
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.event.ErrorEvent
import com.github.jing332.tts.synthesizer.event.Event
import com.github.jing332.tts.synthesizer.event.IEventDispatcher
import com.github.jing332.tts.synthesizer.event.NormalEvent
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.MainActivity
import com.github.jing332.tts_server_android.conf.SysTtsConfig
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.constant.SystemNotificationConst
import com.github.jing332.tts_server_android.service.systts.SystemTtsService.Companion.ACTION_NOTIFY_CANCEL
import com.github.jing332.tts_server_android.service.systts.SystemTtsService.Companion.ACTION_NOTIFY_KILL_PROCESS
import com.github.jing332.tts_server_android.service.systts.SystemTtsService.Companion.ACTION_UPDATE_CONFIG
import com.github.jing332.tts_server_android.service.systts.SystemTtsService.Companion.ACTION_UPDATE_REPLACER
import com.github.jing332.tts_server_android.service.systts.SystemTtsService.Companion.NOTIFICATION_CHAN_ID
import com.github.jing332.tts_server_android.UserTtsLogger
import com.github.jing332.tts_server_android.service.systts.help.TextProcessor
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.runCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.nio.ByteBuffer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.jvm.Throws
import kotlin.system.exitProcess


@Suppress("DEPRECATION")
class SystemTtsService : TextToSpeechService(), IEventDispatcher {
    companion object {
        const val TAG = "SystemTtsService"
        private val logger = KotlinLogging.logger(TAG)

        const val ACTION_UPDATE_CONFIG = "tts.update_config"
        const val ACTION_UPDATE_REPLACER = "tts.update_replacer"

        const val ACTION_NOTIFY_CANCEL = "tts.notification.cancel"
        const val ACTION_NOTIFY_KILL_PROCESS = "tts.notification.exit"
        const val NOTIFICATION_CHAN_ID = "system_tts_service"

        const val DEFAULT_VOICE_NAME = "DEFAULT_默认"
        const val PARAM_BGM_ENABLED = "bgm_enabled"

        /**
         * 更新配置
         */
        fun notifyUpdateConfig(isOnlyReplacer: Boolean = false) {
            if (isOnlyReplacer)
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_REPLACER))
            else
                AppConst.localBroadcast.sendBroadcast(Intent(ACTION_UPDATE_CONFIG))
        }
    }

    private val mCurrentLanguage: MutableList<String> = mutableListOf("zho", "CHN", "")


    private val mTextProcessor = TextProcessor()
    private var mTtsManager: MixSynthesizer? = null


    private val mNotificationReceiver: NotificationReceiver by lazy { NotificationReceiver() }
    private val mLocalReceiver: LocalReceiver by lazy { LocalReceiver() }

    private lateinit var mScope: CoroutineScope


    // WIFI 锁
    private val mWifiLock by lazy {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tts-server:wifi_lock")
    }

    // 唤醒锁
    private var mWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        updateNotification(getString(R.string.systts_service), "")
        mScope = CoroutineScope(Dispatchers.IO)

        registerGlobalReceiver(
            listOf(ACTION_NOTIFY_KILL_PROCESS, ACTION_NOTIFY_CANCEL), mNotificationReceiver
        )

        AppConst.localBroadcast.registerReceiver(
            mLocalReceiver,
            IntentFilter(ACTION_UPDATE_CONFIG).apply {
                addAction(ACTION_UPDATE_REPLACER)
            }
        )

        if (SysTtsConfig.isWakeLockEnabled)
            mWakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "tts-server:wake_lock"
            )

        mWakeLock?.acquire(60 * 20 * 100)
        mWifiLock.acquire()


        initManager()
    }

    fun initManager() {
        logger.debug { "initialize or load configruation" }
        mScope.launch {
            mTtsManager = mTtsManager ?: MixSynthesizer.global.apply {
                context.androidContext = appCtx
                context.event = this@SystemTtsService
                context.cfg = SynthesizerConfig(
                    requestTimeout = SysTtsConfig::requestTimeout,
                    maxRetryTimes = SysTtsConfig::maxRetryCount,
                    streamPlayEnabled = SysTtsConfig::isStreamPlayModeEnabled,
                    silenceSkipEnabled = SysTtsConfig::isSkipSilentAudio,
                    bgmShuffleEnabled = SysTtsConfig::isBgmShuffleEnabled,
                    bgmVolume = SysTtsConfig::bgmVolume,
                    audioParams = {
                        AudioParams(
                            speed = SysTtsConfig.audioParamsSpeed,
                            volume = SysTtsConfig.audioParamsVolume,
                            pitch = SysTtsConfig.audioParamsPitch
                        )
                    }
                )
                textProcessor = mTextProcessor
            }

            mTtsManager!!.init()
        }
    }

    fun loadReplacer() {
        mTextProcessor.loadReplacer()
    }

    override fun onDestroy() {
        logger.debug { "service destroy" }
        super.onDestroy()

        mScope.launch(Dispatchers.Main) {
            mTtsManager?.destroy()
            mTtsManager = null
            logger.debug { "destoryed" }
        }
        unregisterReceiver(mNotificationReceiver)
        AppConst.localBroadcast.unregisterReceiver(mLocalReceiver)

        mWakeLock?.release()
        mWifiLock.release()

        stopForeground(/* removeNotification = */ true)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (Locale.SIMPLIFIED_CHINESE.isO3Language == lang || Locale.US.isO3Language == lang) {
            if (Locale.SIMPLIFIED_CHINESE.isO3Country == country || Locale.US.isO3Country == country) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return mCurrentLanguage.toTypedArray()
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        mCurrentLanguage.clear()
        mCurrentLanguage.addAll(
            mutableListOf(
                lang.toString(),
                country.toString(),
                variant.toString()
            )
        )

        return result
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String {
        return DEFAULT_VOICE_NAME
    }


    override fun onGetVoices(): MutableList<Voice> {
        val list =
            mutableListOf(Voice(DEFAULT_VOICE_NAME, Locale.getDefault(), 0, 0, true, emptySet()))

        dbm.systemTtsV2.getAllGroupWithTts().forEach { groups ->
            groups.list.forEach { it ->
                if (it.config is TtsConfigurationDTO) {
                    val tts = (it.config as TtsConfigurationDTO).source

                    list.add(
                        Voice(
                            /* name = */ "${it.displayName}_${it.id}",
                            /* locale = */ Locale.forLanguageTag(tts.locale),
                            /* quality = */ 0,
                            /* latency = */ 0,
                            /* requiresNetworkConnection = */true,
                            /* features = */mutableSetOf<String>().apply {
                                add(it.order.toString())
                                add(it.id.toString())
                            }
                        )
                    )
                }

            }
        }

        return list
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        val isDefault = voiceName == DEFAULT_VOICE_NAME
        if (isDefault) return TextToSpeech.SUCCESS

        val index =
            dbm.systemTtsV2.all.indexOfFirst { "${it.displayName}_${it.id}" == voiceName }

        return if (index == -1) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }

    override fun onStop() {
        logger.debug { getString(R.string.cancel) }
        synthesizerJob?.cancel()
        synthesizerJob = null
        updateNotification(getString(R.string.systts_state_idle), "")
    }

    private lateinit var mCurrentText: String
    private var synthesizerJob: Job? = null
    private var mNotificationJob: Job? = null


    private fun getConfigIdFromVoiceName(voiceName: String): Result<Long?, Unit> {
        if (voiceName.isNotBlank()) {
            val voiceSplitList = voiceName.split("_")
            if (voiceSplitList.isEmpty()) {
                return Err(Unit)
            } else {
                voiceSplitList.getOrNull(voiceSplitList.size - 1)?.let { idStr ->
                    return Ok(idStr.toLongOrNull())
                }
            }
        }
        return Ok(null)
    }


    override fun onSynthesizeText(
        request: SynthesisRequest,
        callback: android.speech.tts.SynthesisCallback,
    ) {
        var callbackStarted = false
        var callbackFinished = false

        fun safeStart(sampleRate: Int = 16000): Boolean {
            if (callbackStarted) return true
            if (callbackFinished) return false

            val safeSampleRate = if (sampleRate > 0) sampleRate else 16000

            val result = try {
                callback.start(
                    /* sampleRateInHz = */ safeSampleRate,
                    /* audioFormat = */ AudioFormat.ENCODING_PCM_16BIT,
                    /* channelCount = */ 1
                )
            } catch (e: Throwable) {
                logE("callback.start failed: ${e.stackTraceToString()}")
                TextToSpeech.ERROR
            }

            return if (result == TextToSpeech.SUCCESS) {
                callbackStarted = true
                true
            } else {
                try {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                } catch (_: Throwable) {
                }
                callbackFinished = true
                false
            }
        }

        fun safeDone() {
            if (callbackFinished) return

            if (!callbackStarted) {
                if (!safeStart(16000)) return
            }

            try {
                callback.done()
            } catch (e: Throwable) {
                logE("callback.done failed: ${e.stackTraceToString()}")
            }

            callbackFinished = true
        }

        fun safeError(errorCode: Int = TextToSpeech.ERROR_SYNTHESIS) {
            if (callbackFinished) return

            try {
                callback.error(errorCode)
            } catch (e: Throwable) {
                logE("callback.error failed: ${e.stackTraceToString()}")
            }

            callbackFinished = true
        }

        fun isSpeakableText(value: String): Boolean {
            return value.any { it.isLetterOrDigit() }
        }

        fun finishWithTinySilence() {
            if (!safeStart(16000)) return

            val sampleRate = 16000
            val durationMs = 20
            val bytesPerSample = 2
            val sampleCount = sampleRate * durationMs / 1000
            val silence = ByteArray(sampleCount * bytesPerSample)

            try {
                callback.audioAvailable(silence, 0, silence.size)
            } catch (e: Throwable) {
                logE("tiny silence audioAvailable failed: ${e.stackTraceToString()}")
            }

            safeDone()
        }

        val text = request.charSequenceText.toString().trim()

        if (text.isBlank() || !isSpeakableText(text)) {
            logger.debug { "Skip blank or punctuation-only text request: $text" }
            finishWithTinySilence()
            return
        }

        mNotificationJob?.cancel()
        reNewWakeLock()
        startForegroundService()
        mCurrentText = text
        updateNotification(getString(R.string.systts_state_synthesizing), text)

        val enabledBgm = request.params.getBoolean(PARAM_BGM_ENABLED, true)
        mTtsManager?.context?.cfg?.bgmEnabled = { enabledBgm }

        runBlocking {
            val cfgId: Long? = getConfigIdFromVoiceName(request.voiceName ?: "").onFailure {
                longToast(R.string.voice_name_bad_format)
                safeError(TextToSpeech.ERROR_INVALID_REQUEST)
                return@runBlocking
            }.value

            synthesizerJob = mScope.launch {
                val manager = mTtsManager

                if (manager == null) {
                    safeError(TextToSpeech.ERROR_SYNTHESIS)
                    return@launch
                }

                manager.synthesize(
                    params = SystemParams(text = request.charSequenceText.toString()),
                    forceConfigId = cfgId,
                    callback = object : com.github.jing332.tts.synthesizer.SynthesisCallback {
                        override fun onSynthesizeStart(sampleRate: Int) {
                            safeStart(sampleRate)
                        }

                        override fun onSynthesizeAvailable(audio: ByteArray) {
                            if (audio.isEmpty()) return

                            if (!callbackStarted) {
                                if (!safeStart(16000)) return
                            }

                            writeToCallBack(callback, audio)
                        }
                    }
                ).onSuccess {
                    logger.debug { "done" }
                    safeDone()
                }.onFailure {
                    when (it) {
                        SynthesisError.ConfigEmpty -> {
                            safeError(TextToSpeech.ERROR_SYNTHESIS)
                        }

                        is SynthesisError.TextHandle -> {
                            safeError(TextToSpeech.ERROR_SYNTHESIS)
                        }

                        is SynthesisError.PresetMissing -> {
                            logE(R.string.tts_config_not_exist)
                            longToast(R.string.tts_config_not_exist)
                            safeError(TextToSpeech.ERROR_INVALID_REQUEST)
                        }
                    }
                }
            }

            synthesizerJob?.join()
        }

        mNotificationJob = mScope.launch {
            delay(5000)
            stopForeground(true)
            mNotificationDisplayed = false
        }
    }
    private fun writeToCallBack(
        callback: android.speech.tts.SynthesisCallback,
        pcmData: ByteArray,
    ) {
        try {
            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < pcmData.size && mTtsManager!!.isSynthesizing) {
                val bytesToWrite = maxBufferSize.coerceAtMost(pcmData.size - offset)
                callback.audioAvailable(pcmData, offset, bytesToWrite)
                offset += bytesToWrite
            }
        } catch (e: Exception) {
            logE("writeToCallBack: ${e.toString()}")
        }
    }

    private fun reNewWakeLock() {
        if (mWakeLock != null && mWakeLock?.isHeld == false) {
            mWakeLock?.acquire(60 * 20 * 1000)
        }
    }

    private var mNotificationBuilder: Notification.Builder? = null

    // 通知是否显示中
    private var mNotificationDisplayed = false

    /* 启动前台服务通知 */
    private fun startForegroundService() {
        if (SysTtsConfig.isForegroundServiceEnabled && !mNotificationDisplayed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    NOTIFICATION_CHAN_ID,
                    getString(R.string.systts_service),
                    NotificationManager.IMPORTANCE_NONE
                )
                chan.lightColor = Color.CYAN
                chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE

                notificationManager.createNotificationChannel(chan)
            }
            val notifi = getNotification()

            startForegroundCompat(SystemNotificationConst.ID_SYSTEM_TTS, notifi)
            mNotificationDisplayed = true
        }
    }

    /* 更新通知 */
    private fun updateNotification(title: String, content: String? = null) {
        if (SysTtsConfig.isForegroundServiceEnabled)
            runOnUI {
                mNotificationBuilder?.let { builder ->
                    content?.let {
                        val bigTextStyle =
                            Notification.BigTextStyle().bigText(it).setSummaryText("TTS")
                        builder.style = bigTextStyle
                        builder.setContentText(it)
                    }

                    builder.setContentTitle(title)
                    startForegroundCompat(
                        SystemNotificationConst.ID_SYSTEM_TTS,
                        builder.build()
                    )
                }
            }
    }

    /* 获取通知 */
    @Suppress("DEPRECATION")
    private fun getNotification(): Notification {
        val notification: Notification
        /*Android 12(S)+ 必须指定PendingIntent.FLAG_*/
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        /*点击通知跳转*/
        val pendingIntent =
            PendingIntent.getActivity(
                this, 1, Intent(
                    this,
                    MainActivity::class.java
                ).apply { /*putExtra(KEY_FRAGMENT_INDEX, INDEX_SYS_TTS)*/ }, pendingIntentFlags
            )

        val killProcessPendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(
                ACTION_NOTIFY_KILL_PROCESS
            ), pendingIntentFlags
        )
        val cancelPendingIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_NOTIFY_CANCEL),
                pendingIntentFlags
            )

        mNotificationBuilder = Notification.Builder(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationBuilder?.setChannelId(NOTIFICATION_CHAN_ID)
        }
        notification = mNotificationBuilder!!
            .setSmallIcon(R.mipmap.ic_app_notification)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_light_primary))
            .addAction(0, getString(R.string.kill_process), killProcessPendingIntent)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent)
            .build()

        return notification
    }

    @Suppress("DEPRECATION")
    inner class NotificationReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_NOTIFY_KILL_PROCESS -> { // 通知按钮{结束进程}
                    stopForeground(true)
                    exitProcess(0)
                }

                ACTION_NOTIFY_CANCEL -> { // 通知按钮{取消}
                    if (mTtsManager!!.isSynthesizing)
                        onStop() /* 取消当前播放 */
                    else /* 无播放，关闭通知 */ {
                        stopForeground(true)
                        mNotificationDisplayed = false
                    }
                }
            }
        }
    }

    inner class LocalReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_CONFIG -> initManager()
                ACTION_UPDATE_REPLACER -> loadReplacer()
            }
        }
    }

    private fun logD(msg: String) = logger.debug(msg)
    private fun logI(msg: String) = logger.info(msg)
    private fun logW(msg: String) = logger.warn(msg)
    private fun logE(msg: String, throwable: Throwable? = null) {
        updateNotification("⚠️ " + getString(R.string.error), msg)
        Log.e(TAG, msg, throwable)

        logger.error(msg)
    }

    @Throws(Resources.NotFoundException::class)
    private fun logE(@StringRes strId: Int, throwable: Throwable? = null) {
        logE(getString(strId, throwable), throwable)
    }

    override fun dispatch(event: Event) {
        when (event) {
            is ErrorEvent -> errorEvent(event)
            is NormalEvent -> normalEvent(event)
            else -> {
                logE("Unknown event: $event")
            }
        }
    }

    private fun RequestPayload.text(): String {
        val tag = config.tag
        val standbyTag = config.standbyConfig?.tag

        val standbyInfo = if (standbyTag is SystemTtsV2) {
            "<br>${getString(R.string.systts_standby)} " + standbyTag.displayName
        } else {
            ""
        }

        val configText = if (tag is SystemTtsV2) {
            tag.displayName + ", ${config.source.voice}, ${config.speechInfo.tagName}" + standbyInfo.toHtmlSmall()
        } else {
            ""
        }

        return text.toHtmlBold() + "<br>" + configText
    }

    private fun RequestPayload.userLogVoiceName(): String {
        val tag = config.tag
        val standbyTag = config.standbyConfig?.tag

        val mainVoice = if (tag is SystemTtsV2) {
            tag.displayName
        } else {
            try {
                config.source.voice.takeIf { it.isNotBlank() } ?: "未知音色"
            } catch (_: Throwable) {
                "未知音色"
            }
        }

        val standbyVoice = if (standbyTag is SystemTtsV2) {
            " / 备用：${standbyTag.displayName}"
        } else {
            ""
        }

        return mainVoice + standbyVoice
    }
    private data class CharacterRecordLog(
        val name: String,
        val aliases: String,
        val gender: String,
        val age: String,
        val voice: String,
        val rawText: String,
    )

    private fun JSONObject.optAnyString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
    private fun JSONObject.collectAllStringValues(): List<String> {
        val result = mutableListOf<String>()

        fun collect(value: Any?) {
            when (value) {
                is String -> {
                    if (value.isNotBlank()) result.add(value)
                }

                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        collect(value.opt(keys.next()))
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        collect(value.opt(i))
                    }
                }

                null -> Unit

                else -> {
                    val text = value.toString()
                    if (text.isNotBlank() && text != "null") result.add(text)
                }
            }
        }

        collect(this)
        return result
    }

    private fun parseCharacterRecordObject(
        obj: JSONObject,
        fallbackName: String = "",
    ): CharacterRecordLog {
        val allText = obj.collectAllStringValues()
            .joinToString(" | ")
            .trim()

        val name = obj.optAnyString(
            "name",
            "roleName",
            "characterName",
            "character",
            "speaker",
            "speakerName",
            "角色",
            "角色名",
            "人物",
            "人物名",
            "称呼"
        ).ifBlank { fallbackName }

        val aliases = obj.optAnyString(
            "aliases",
            "alias",
            "别名",
            "别称",
            "aliasesText"
        )

        val gender = obj.optAnyString(
            "gender",
            "sex",
            "性别"
        )

        val age = obj.optAnyString(
            "age",
            "ageGroup",
            "年龄",
            "年龄段"
        )

        val voice = obj.optAnyString(
            "voice",
            "voiceName",
            "tts",
            "ttsName",
            "selectedVoice",
            "selectedTts",
            "selectedTtsName",
            "assignedVoice",
            "voiceLabel",
            "tagName",
            "tag",
            "音色",
            "分配音色",
            "角色分配音色",
            "已选音色"
        ).ifBlank {
            allText
        }

        return CharacterRecordLog(
            name = name.trim(),
            aliases = aliases.trim(),
            gender = gender.trim(),
            age = age.trim(),
            voice = voice.trim(),
            rawText = allText
        )
    }
    private fun htmlEscapeForLog(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun normalizeLogText(text: String): String {
        return text
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
    }

    private fun findCharacterRecordsFile(): File? {
        return try {
            val candidates = mutableListOf<File>()

            // 新版 APK 共享角色数据目录
            candidates.add(File(filesDir, "role_manager_shared/characterRecords.json"))
            candidates.add(File(filesDir, "role_manager_shared/characterRecaords.json"))

            // 兼容旧插件共享目录
            val root = AppConst.externalFilesDir
            candidates.add(File(root, "plugins/mingwuyan/characterRecords.json"))
            candidates.add(File(root, "plugins/mingwuyan/characterRecaords.json"))

            candidates
                .filter { it.exists() && it.isFile && it.length() > 2L }
                .maxByOrNull { it.lastModified() }
                ?: root.walkTopDown()
                    .maxDepth(12)
                    .filter {
                        it.isFile &&
                                (
                                        it.name.equals("characterRecords.json", ignoreCase = true) ||
                                                it.name.equals("characterRecaords.json", ignoreCase = true)
                                        )
                    }
                    .filter { it.length() > 2L }
                    .maxByOrNull { it.lastModified() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun aliasMatches(aliases: String, roleName: String): Boolean {
        if (aliases.isBlank() || roleName.isBlank()) return false

        return aliases
            .split("|", "｜", ",", "，", "、", "/", " ")
            .map { it.trim() }
            .any { it == roleName }
    }

    private fun findCharacterRecord(roleName: String): CharacterRecordLog? {
        val name = roleName.trim()
        if (name.isBlank()) return null

        return readCharacterRecords().firstOrNull { record ->
            record.name == name || aliasMatches(record.aliases, name)
        }
    }

    private fun readCharacterRecords(): List<CharacterRecordLog> {
        return try {
            val file = findCharacterRecordsFile() ?: return emptyList()
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) return emptyList()

            val result = mutableListOf<CharacterRecordLog>()

            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    result.add(parseCharacterRecordObject(obj))
                }
            } else if (text.startsWith("{")) {
                val root = JSONObject(text)

                val arrayKeys = listOf(
                    "records",
                    "characters",
                    "characterRecords",
                    "characterRecaords",
                    "list",
                    "data"
                )

                var parsedArray = false
                for (key in arrayKeys) {
                    val array = root.optJSONArray(key)
                    if (array != null) {
                        parsedArray = true
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i) ?: continue
                            result.add(parseCharacterRecordObject(obj))
                        }
                    }
                }

                if (!parsedArray) {
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = root.optJSONObject(key) ?: continue
                        result.add(parseCharacterRecordObject(obj, fallbackName = key))
                    }
                }
            }

            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private data class CurrentTtsLogInfo(
        val displayName: String,
        val sourceVoice: String,
        val tag: String,
        val tagName: String,
        val personality: String,
    )

    private fun normalizeVoiceMatchText(text: String): String {
        return text
            .lowercase()
            .replace("【", "")
            .replace("】", "")
            .replace("[", "")
            .replace("]", "")
            .replace("（", "")
            .replace("）", "")
            .replace("(", "")
            .replace(")", "")
            .replace("《", "")
            .replace("》", "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace(" ", "")
            .replace("　", "")
            .replace("-", "")
            .replace("_", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("|", "")
            .replace("｜", "")
            .replace("，", "")
            .replace(",", "")
            .replace("、", "")
            .trim()
    }

    private fun RequestPayload.currentTtsLogInfo(): CurrentTtsLogInfo {
        val tts = config.tag as? SystemTtsV2
        val ttsConfig = tts?.config as? TtsConfigurationDTO
        val speechInfo = config.speechInfo

        val displayName = tts?.displayName?.trim().orEmpty()

        val sourceVoice = try {
            config.source.voice.trim()
        } catch (_: Throwable) {
            try {
                ttsConfig?.source?.voice?.trim().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }

        return CurrentTtsLogInfo(
            displayName = displayName,
            sourceVoice = sourceVoice,
            tag = speechInfo.tag.trim(),
            tagName = speechInfo.tagName.trim(),
            personality = speechInfo.tagData["personality"]?.trim().orEmpty()
        )
    }

    private fun voiceRecordMatchesCurrentTts(
        record: CharacterRecordLog,
        info: CurrentTtsLogInfo,
    ): Boolean {
        val recordTexts = listOf(
            record.voice,
            record.rawText
        ).map {
            normalizeVoiceMatchText(it)
        }.filter {
            it.isNotBlank()
        }

        if (recordTexts.isEmpty()) return false

        val candidates = listOf(
            info.displayName,
            info.sourceVoice,
            info.tag,
            info.tagName,
            info.personality,
            "${info.tagName}${info.personality}",
            "${info.tagName}${info.displayName}",
            "${info.tagName} - ${info.personality}",
            "${info.tagName} - ${info.displayName}"
        ).map {
            normalizeVoiceMatchText(it)
        }.filter {
            it.isNotBlank()
        }

        return recordTexts.any { recordText ->
            candidates.any { candidate ->
                candidate.contains(recordText) || recordText.contains(candidate)
            }
        }
    }

    private fun findCharacterRecordByCurrentTts(info: CurrentTtsLogInfo): CharacterRecordLog? {
        return readCharacterRecords().firstOrNull { record ->
            voiceRecordMatchesCurrentTts(record, info)
        }
    }

    private fun extractTagLabelOnly(value: String): String {
        val text = value.trim()
        if (text.isBlank()) return ""

        val bracket = Regex("【([^】]+)】")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (bracket.isNotBlank()) return bracket

        val beforeDash = text
            .substringBefore(" - ")
            .substringBefore("-")
            .trim()
            .removePrefix("【")
            .removeSuffix("】")
            .trim()

        if (beforeDash.contains("/")) return beforeDash
        if (beforeDash.contains("旁白")) return "旁白"

        return tagNameFromPureTag(beforeDash).ifBlank {
            beforeDash
        }
    }

    private fun formatTagOnlyForLog(tagName: String, tag: String): String {
        val label = extractTagLabelOnly(tagName).ifBlank {
            tagNameFromPureTag(tag)
        }.trim()

        if (label.isBlank()) return ""
        if (label == "旁白") return "旁白"

        return "【$label】"
    }

    private fun tagNameFromPureTag(tag: String): String {
        val value = tag.trim()
        if (value.isBlank()) return ""

        val femalePrefixes = listOf("女童", "少女", "女青年", "女中年", "女老年", "女主")
        val malePrefixes = listOf("男童", "少年", "男青年", "男中年", "男老年", "男主")

        if (femalePrefixes.any { value.startsWith(it) }) {
            return "女/$value"
        }

        if (malePrefixes.any { value.startsWith(it) }) {
            return "男/$value"
        }

        return ""
    }

    private fun genderAgeLabel(gender: String, age: String): String {
        val g = gender.trim()
        val a = age.trim()

        if (g.isBlank() || a.isBlank()) return ""

        if (a == "旁白") return "旁白"

        val agePart = if (a.startsWith(g)) {
            a
        } else {
            "$g$a"
        }

        return "$g/$agePart"
    }

    private fun findTtsAssignmentByVoiceName(voiceName: String): String {
        val key = voiceName.trim()
        if (key.isBlank()) return ""

        return try {
            dbm.systemTtsV2.all.asSequence()
                .mapNotNull { tts ->
                    val config = tts.config as? TtsConfigurationDTO ?: return@mapNotNull null
                    val speechRule = config.speechRule

                    val tag = speechRule.tag.trim()
                    val tagName = speechRule.tagName.trim()
                    val personality = speechRule.tagData["personality"]?.trim().orEmpty()
                    val displayName = tts.displayName.trim()

                    val matched =
                        key == displayName ||
                                key == personality ||
                                key == tag ||
                                key == tagName ||
                                displayName.contains(key) ||
                                personality.contains(key)

                    if (!matched) return@mapNotNull null

                    val safeTagName = tagName.ifBlank {
                        tagNameFromPureTag(tag).ifBlank { tag }
                    }

                    val safeVoiceName = personality.ifBlank {
                        displayName.ifBlank { key }
                    }

                    if (safeTagName.isBlank()) {
                        safeVoiceName
                    } else {
                        "$safeTagName - $safeVoiceName"
                    }
                }
                .firstOrNull()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun RequestPayload.roleNameForUserLog(): String {
        return try {
            val tagData = config.speechInfo.tagData
            val keys = listOf(
                "roleName",
                "characterName",
                "character",
                "speakerName",
                "speaker",
                "role",
                "name",
                "人物",
                "角色",
                "角色名",
                "发言人"
            )

            // 1. 优先使用朗读规则传出的角色名
            for (key in keys) {
                val value = tagData[key]?.trim().orEmpty()
                if (value.isNotBlank() && value != "未知发言人" && value != "旁白") {
                    return value
                }
            }

            val info = currentTtsLogInfo()

            // 2. 再按当前实际使用的音色，到角色管理记录里反查角色名
            val record = findCharacterRecordByCurrentTts(info)
            if (record != null && record.name.isNotBlank()) {
                return record.name
            }

            // 3. 如果当前明确是旁白，才显示旁白
            if (
                info.tagName.contains("旁白") ||
                info.tag.contains("旁白") ||
                info.displayName.contains("旁白")
            ) {
                return "旁白"
            }

            // 4. 最后兜底：不要把角色对话强行显示成旁白
            info.personality
                .ifBlank { info.displayName }
                .ifBlank { "未知角色" }
        } catch (_: Throwable) {
            "未知角色"
        }
    }

    private fun RequestPayload.roleVoiceAssignmentForUserLog(roleName: String): String {
        val info = currentTtsLogInfo()
        val role = roleName.trim().ifBlank { "旁白" }

        if (role == "旁白") {
            val voiceName = info.personality
                .ifBlank { info.displayName }
                .ifBlank { userLogVoiceName() }

            return "旁白 - $voiceName"
        }

        val tagOnly = formatTagOnlyForLog(
            tagName = info.tagName,
            tag = info.tag
        )

        if (tagOnly.isNotBlank()) {
            return tagOnly
        }

        val record = findCharacterRecord(role)
        val recordTag = formatTagOnlyForLog(
            tagName = record?.voice.orEmpty(),
            tag = record?.voice.orEmpty()
        )

        if (recordTag.isNotBlank()) {
            return recordTag
        }

        return userLogVoiceName()
    }

    private fun RequestPayload.userReadableRequestLogHtml(): String {
        val cleanText = htmlEscapeForLog(normalizeLogText(text))
        val roleName = roleNameForUserLog()
        val assignment = htmlEscapeForLog(roleVoiceAssignmentForUserLog(roleName))

        return buildString {
            append("请求音频：")
            append(cleanText)
            append("<br>")
            append(htmlEscapeForLog(roleName))
            if (assignment.isNotBlank()) {
                append("<br>")
                append(assignment)
            }
        }
    }

    private fun normalEvent(e: NormalEvent) {
        when (e) {
            is NormalEvent.Request ->
                if (e.retries > 0) {
                    logW(getString(R.string.systts_log_start_retry, e.retries))
                } else {
                    val roleName = e.request.roleNameForUserLog()
                    val assignment = e.request.roleVoiceAssignmentForUserLog(roleName)

                    UserTtsLogger.logSpeak(
                        text = e.request.text,
                        voiceName = e.request.userLogVoiceName()
                    )

                    logI(e.request.userReadableRequestLogHtml())
                }

            is NormalEvent.DirectPlay -> logI(
                getString(
                    R.string.systts_log_direct_play,
                    e.request.text()
                )
            )

            is NormalEvent.ReadAllFromStream -> {
                if (e.size > 0)
                    logI(
                        getString(
                            R.string.systts_log_success,
                            e.size.sizeToReadable(),
                            "${e.costTime}ms"
                        ) + "<br> ${e.request.text()}"
                    )
            }

            is NormalEvent.HandleStream ->
                logI(
                    getString(
                        R.string.loading_audio_stream,
                        e.request.text.limitLength(10)
                    )
                )

            is NormalEvent.StandbyTts -> logI(
                getString(
                    R.string.use_standby_tts, e.request.text()
                )
            )

            NormalEvent.RequestCountEnded -> logW(getString(R.string.reach_retry_limit))
            is NormalEvent.BgmCurrentPlaying -> {
                val name = e.source.path.split("/").lastOrNull() ?: e.source.path
                logI(getString(R.string.current_playing_bgm, "${e.source.volume}, ${name}"))
            }
        }
    }

    private fun errorEvent(e: ErrorEvent) {
        when (e) {
            is ErrorEvent.TextProcessor -> handleTextProcessorError(e.error)
            is ErrorEvent.Request -> logE(R.string.systts_log_failed, e.cause)
            is ErrorEvent.RequestTimeout -> logW(
                getString(
                    R.string.failed_timed_out,
                    SysTtsConfig.requestTimeout
                )
            )

            ErrorEvent.ConfigEmpty -> {
                logE(R.string.config_empty_error)
            }

            is ErrorEvent.BgmLoading -> {
                logE(R.string.config_load_error, e.cause)
            }

            is ErrorEvent.Repository -> {
                logE(R.string.config_load_error, e.cause)
            }

            is ErrorEvent.DirectPlay -> logE(getString(R.string.systts_log_direct_play, e.cause))
            is ErrorEvent.ResultProcessor -> e.error.let { processor ->
                when (processor) {
                    is StreamProcessorError.AudioDecoding -> logE(
                        getString(
                            R.string.audio_decoding_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )

                    is StreamProcessorError.AudioSource -> logE(
                        getString(
                            R.string.audio_source_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )

                    is StreamProcessorError.HandleError -> logE(
                        getString(
                            R.string.stream_handle_error,
                            processor.error.toString() + "<br>" + e.request.text()
                        )
                    )
                }
            }
        }
    }

    fun ConfigType.toLocaleString() = when (this) {
        ConfigType.SINGLE_VOICE -> getString(R.string.single_voice)
        ConfigType.TAG -> getString(R.string.tag)
    }

    private fun handleTextProcessorError(err: TextProcessorError) {
        when (err) {
            is TextProcessorError.HandleText -> logE(
                R.string.systts_log_text_handle_failed,
                err.error
            )

            is TextProcessorError.MissingConfig -> {
                val str = getString(R.string.missing_config, err.type.toLocaleString())
                longToast(str)
                logE(str)
            }

            is TextProcessorError.MissingRule -> {
                getString(
                    R.string.missing_speech_rule,
                    err.id.ifBlank { getString(R.string.none) }
                ).let {
                    logE(it)
                    longToast(StringUtils.WARNING_EMOJI + " " + it)
                }

            }

            TextProcessorError.Initialization -> logE(getString(R.string.text_processor_init_failed))
        }
    }

}