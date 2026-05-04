package com.github.jing332.tts_server_android.compose.systts.list.avatar

fun resolveVoiceAvatarUri(
    packageName: String,
    voiceName: String,
    voiceId: String,
    pluginIcon: String?
): String {
    val icon = pluginIcon.orEmpty().trim()
    if (isUsableAvatarUri(icon)) return icon

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
        hasAny(lower, "旁白", "narration", "解说", "讲述", "默认", "通用") ->
            "avatar_narration"

        hasAny(lower, "女童", "小女孩", "女孩", "儿童女", "girl_child", "child female") ->
            "avatar_girl_child_pigtails"

        hasAny(lower, "男童", "小男孩", "男孩", "儿童男", "boy_child", "child male") ->
            "avatar_boy_child"

        hasAny(lower, "女老年", "老年女", "老人女", "奶奶", "老奶奶", "female old") ->
            "avatar_female_old"

        hasAny(lower, "男老年", "老年男", "老人男", "爷爷", "老爷爷", "male old") ->
            "avatar_male_old"

        hasAny(lower, "女中年", "中年女", "阿姨", "middle female") ->
            "avatar_female_middle"

        hasAny(lower, "男中年", "中年男", "叔叔", "大叔", "middle male") ->
            "avatar_male_middle"

        hasAny(lower, "御姐", "成熟", "成熟女", "姐姐", "女王") ->
            "avatar_female_mature"

        hasAny(lower, "少女", "女少年", "甜美", "软萌", "元气女孩") ->
            "avatar_girl_teen"

        hasAny(lower, "少年", "少男", "男少年", "teen boy") ->
            "avatar_boy_teen"

        hasAny(lower, "女青年", "青年女", "年轻女", "female young") ->
            "avatar_female_young"

        hasAny(lower, "男青年", "青年男", "年轻男", "male young") ->
            "avatar_male_young"

        hasAny(lower, "女", "female") ->
            "avatar_female_young"

        hasAny(lower, "男", "male") ->
            "avatar_male_young"

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
