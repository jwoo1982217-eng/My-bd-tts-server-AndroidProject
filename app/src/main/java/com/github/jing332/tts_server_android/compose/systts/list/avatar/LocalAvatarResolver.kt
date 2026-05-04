package com.github.jing332.tts_server_android.compose.systts.list.avatar

import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.tts_server_android.R

fun resolveLocalAvatarRes(tts: SystemTtsV2): Int {
    val text = listOf(
        tts.displayName,
        tts.config.toString()
    ).joinToString(" ").lowercase()

    return when {
        hasAny(text, "旁白", "narration", "讲述", "解说", "默认", "通用") ->
            R.drawable.avatar_narration

        hasAny(text, "男童", "小男孩", "男孩", "儿童男", "boy_child") ->
            R.drawable.avatar_boy_child

        hasAny(text, "女童", "小女孩", "羊角辫", "儿童女", "girl_child") ->
            R.drawable.avatar_girl_child_pigtails

        hasAny(text, "少年", "少男", "男少年", "teen boy") &&
                hasAny(text, "活泼", "元气", "开朗") ->
            R.drawable.avatar_boy_active

        hasAny(text, "少年", "少男", "男少年", "teen boy") &&
                hasAny(text, "高冷", "冷淡", "清冷") ->
            R.drawable.avatar_boy_cool

        hasAny(text, "少年", "少男", "男少年", "teen boy") &&
                hasAny(text, "温柔", "柔和") ->
            R.drawable.avatar_boy_gentle

        hasAny(text, "少年", "少男", "男少年", "teen boy") ->
            R.drawable.avatar_boy_teen

        hasAny(text, "男老年", "老年男", "老人男", "爷爷", "老爷爷", "male old") ->
            R.drawable.avatar_male_old

        hasAny(text, "女老年", "老年女", "老人女", "奶奶", "老奶奶", "female old") ->
            R.drawable.avatar_female_old

        hasAny(text, "男中年", "中年男", "叔叔", "大叔", "middle male") ->
            R.drawable.avatar_male_middle

        hasAny(text, "女中年", "中年女", "阿姨", "middle female") ->
            R.drawable.avatar_female_middle

        hasAny(text, "男青年", "青年男", "年轻男", "男大", "先生", "male young") ->
            R.drawable.avatar_male_young

        hasAny(text, "女青年", "青年女", "年轻女", "女大", "female young") ->
            R.drawable.avatar_female_young

        hasAny(text, "御姐", "成熟", "成熟女", "姐姐", "女王") ->
            R.drawable.avatar_female_mature

        hasAny(text, "少女", "女孩", "女孩子", "女") &&
                hasAny(text, "甜美", "甜", "可爱") ->
            R.drawable.avatar_girl_sweet

        hasAny(text, "少女", "女孩", "女孩子", "女") &&
                hasAny(text, "元气", "活泼", "开朗") ->
            R.drawable.avatar_girl_energetic

        hasAny(text, "少女", "女孩", "女孩子", "女") &&
                hasAny(text, "文静", "知性", "书香", "安静") ->
            R.drawable.avatar_girl_literary

        hasAny(text, "少女", "女孩", "女孩子", "女") &&
                hasAny(text, "软萌", "软", "萌") ->
            R.drawable.avatar_girl_soft

        hasAny(text, "少女", "女孩", "女孩子", "女") ->
            R.drawable.avatar_girl_teen

        hasAny(text, "男", "male") ->
            R.drawable.avatar_male_young

        hasAny(text, "女", "female") ->
            R.drawable.avatar_female_young

        else ->
            R.drawable.avatar_default
    }
}

private fun hasAny(text: String, vararg keys: String): Boolean {
    return keys.any { text.contains(it.lowercase()) }
}
