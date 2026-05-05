package com.github.jing332.tts_server_android.compose.systts.list.avatar

fun resolveVoiceAvatarUri(
    packageName: String,
    voiceName: String,
    voiceId: String,
    pluginIcon: String?
): String {
    val icon = pluginIcon.orEmpty().trim()

    // 1. 插件自己带头像，优先使用插件头像
    if (isUsableAvatarUri(icon)) return icon

    // 2. 插件没有头像，走本地男女老幼/旁白/音效头像规则
    return resolveLocalAvatarUri(
        packageName = packageName,
        text = "$voiceName $voiceId"
    )
}

fun resolveLocalAvatarUri(
    packageName: String,
    text: String
): String {
    val lower = text.lowercase()

    val resName = when {
        hasAny(lower, "在线音效", "网络音效", "online", "online_effect", "remote effect") ->
            "tts_avatar_online_effect"

        hasAny(lower, "本地音效", "本地", "local", "local_effect") ->
            "tts_avatar_local_effect"

        hasAny(lower, "旁白", "narration", "narrator", "解说", "讲述", "播报") ->
            "tts_avatar_narration"

        hasAny(lower, "女童", "小女孩", "女孩", "儿童女", "girl_child", "child female") ->
            "tts_avatar_female_child"

        hasAny(lower, "男童", "小男孩", "男孩", "儿童男", "boy_child", "child male") ->
            "tts_avatar_male_child"

        hasAny(lower, "少女", "女少年", "甜美", "软萌", "元气女孩", "teen girl") ->
            "tts_avatar_teen_girl"

        hasAny(lower, "少年", "少男", "男少年", "teen boy") ->
            "tts_avatar_teen_boy"

        hasAny(lower, "女青年", "女青", "青年女", "年轻女", "female young") ->
            "tts_avatar_female_young"

        hasAny(lower, "男青年", "男青", "青年男", "年轻男", "male young") ->
            "tts_avatar_male_young"

        hasAny(lower, "女中年", "女中", "中年女", "阿姨", "middle female") ->
            "tts_avatar_female_middle"

        hasAny(lower, "男中年", "男中", "中年男", "叔叔", "大叔", "middle male") ->
            "tts_avatar_male_middle"

        hasAny(lower, "女老年", "女老", "老年女", "老人女", "奶奶", "老奶奶", "female old") ->
            "tts_avatar_female_old"

        hasAny(lower, "男老年", "男老", "老年男", "老人男", "爷爷", "老爷爷", "male old") ->
            "tts_avatar_male_old"

        hasAny(lower, "御姐", "成熟", "成熟女", "姐姐", "女王", "女", "female") ->
            "tts_avatar_female_young"

        hasAny(lower, "男", "male") ->
            "tts_avatar_male_young"

        else ->
            "avatar_default"
    }

    return "android.resource://$packageName/drawable/$resName"
}

private fun hasAny(text: String, vararg keys: String): Boolean {
    return keys.any { text.contains(it.lowercase()) }
}

private fun isUsableAvatarUri(value: String): Boolean {
    return value.startsWith("http://") ||
        value.startsWith("https://") ||
        value.startsWith("file://") ||
        value.startsWith("content://") ||
        value.startsWith("android.resource://") ||
        value.startsWith("data:image") ||
        value.startsWith("/")
}
