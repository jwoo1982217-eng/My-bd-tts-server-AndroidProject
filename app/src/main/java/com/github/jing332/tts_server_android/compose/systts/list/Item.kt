package com.github.jing332.tts_server_android.compose.systts.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Output
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.github.jing332.common.utils.StringUtils.limitLength
import com.github.jing332.common.utils.performLongPress
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.conf.AppConfig
import org.burnoutcrew.reorderable.ReorderableLazyListState
import org.burnoutcrew.reorderable.detectReorder

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Item(
    modifier: Modifier,
    name: String,
    avatarRes: Int = R.drawable.avatar_default,
    avatarUrl: String? = null,
    tagName: String,
    type: String,
    desc: String,
    params: String,
    audioSampleRate: Int = 0,
    reorderState: ReorderableLazyListState,
    standby: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onAudition: () -> Unit,
    onExport: () -> Unit,
    onAvatarClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val context = LocalContext.current

    val limitNameLen by remember { AppConfig.limitNameLength }
    val titleLine = remember(name, limitNameLen) {
        val raw = if (limitNameLen == 0) name else name.limitLength(limitNameLen)
        oneLine(raw)
    }

    val limitedTagName = remember(tagName) {
        compactDisplayTag(tagName)
    }

    val infoLine = remember(desc, params) {
        compactInfoLine(desc = desc, params = params)
    }

    val sampleRateLine = remember(audioSampleRate, desc, params) {
        if (audioSampleRate > 0) {
            "${audioSampleRate}hz"
        } else {
            extractSampleRateLine(desc = desc, params = params)
        }
    }
    
    val avatarSource = remember(context, name, tagName, type, avatarUrl, avatarRes) {
    selectAvatarSource(
        context = context,
        name = name,
        tagName = tagName,
        type = type,
        avatarUrl = avatarUrl,
        fallback = avatarRes
    )
}

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 128.dp)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.quick_edit),
                    onClick = onClick,
                    onLongClickLabel = stringResource(R.string.switch_tag),
                    onLongClick = {
                        view.performLongPress()
                        onLongClick()
                    }
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    modifier = Modifier
                        .size(34.dp)
                        .detectReorder(reorderState),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .then(
                            if (onAvatarClick != null) {
                                Modifier.clickable { onAvatarClick.invoke() }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatarSource)
                            .crossfade(true)
                            .placeholder(avatarRes)
                            .error(avatarRes)
                            .fallback(avatarRes)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = titleLine,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (limitedTagName.isNotEmpty()) {
                            Text(
                                text = limitedTagName,
                                modifier = Modifier.widthIn(max = 122.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    Text(
                        text = infoLine,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (sampleRateLine.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "声音采样率：$sampleRateLine",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val extraInfo = listOf(
                        type.takeIf { it.isNotBlank() }?.let { "插件：$it" }
                    ).filterNotNull().joinToString("｜")

                    if (extraInfo.isNotBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = extraInfo,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.width(38.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onAudition() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Headphones,
                        contentDescription = stringResource(R.string.audition),
                        modifier = Modifier.size(22.dp)
                    )
                }

                var showOptions by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { showOptions = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_options_desc, name),
                        modifier = Modifier.size(22.dp)
                    )

                    DropdownMenu(
                        expanded = showOptions,
                        onDismissRequest = { showOptions = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = {
                                showOptions = false
                                onEdit()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, null)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.copy)) },
                            onClick = {
                                showOptions = false
                                onCopy()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.CopyAll, null)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.export_config)) },
                            onClick = {
                                showOptions = false
                                onExport()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Output, null)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.delete)) },
                            onClick = {
                                showOptions = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}
private fun oneLine(text: String): String {
    return text
        .replace("<br>", " ")
        .replace("<br/>", " ")
        .replace("<br />", " ")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&lt;[^&]+&gt;"), "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun compactInfoLine(desc: String, params: String): String {
    val source = listOf(
        oneLine(params),
        oneLine(desc)
    ).joinToString(" ")

    val speed = Regex("""语速[:：]?\s*([0-9.]+x?)""")
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { "语速:$it" }
        ?: Regex("""rate[:：]?\s*([0-9.]+x?)""", RegexOption.IGNORE_CASE)
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "语速:$it" }

    val volume = Regex("""音量[:：]?\s*([0-9.]+%?)""")
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { "音量:$it" }
        ?: Regex("""volume[:：]?\s*([0-9.]+%?)""", RegexOption.IGNORE_CASE)
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "音量:$it" }

    val pitch = Regex("""音高[:：]?\s*([0-9.]+)""")
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { "音高:$it" }
        ?: Regex("""pitch[:：]?\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "音高:$it" }

    return listOfNotNull(speed, volume, pitch).joinToString(" | ")
}

private fun extractSampleRateLine(desc: String, params: String): String {
    val source = listOf(
        oneLine(params),
        oneLine(desc)
    ).joinToString(" ")

    return Regex("""\b\d{4,6}\s*hz\b""", RegexOption.IGNORE_CASE)
        .find(source)
        ?.value
        ?.replace(" ", "")
        ?: ""
}

private fun formatTwoLineTag(tag: String): String {
    val clean = oneLine(tag)
    val index = clean.indexOf('】')

    return if (index >= 0 && index < clean.lastIndex) {
        clean.substring(0, index + 1) + "\n" + clean.substring(index + 1).trim()
    } else {
        clean
    }
}
private fun compactDisplayTag(tag: String): String {
    val clean = oneLine(tag)
        .replace("⚠️", "")
        .trim()

    if (clean.isBlank()) return ""

    val bracket = Regex("""【[^】]+】""").find(clean)?.value
    if (!bracket.isNullOrBlank()) return bracket

    val tagLike = Regex(
        """(?:男|女|旁白|在线音效|本地音效)/(?:男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|[^\s】]+)\d{1,3}|(?:男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|旁白)\d{1,3}"""
    ).find(clean)?.value

    if (!tagLike.isNullOrBlank()) {
        return if (tagLike.startsWith("【")) tagLike else "【$tagLike】"
    }

    val first = clean.split(Regex("""\s+""")).firstOrNull().orEmpty()
    return if (first.startsWith("【")) first else "【$first】"
}


private fun formatTwoLinePluginName(type: String): String {
    val clean = oneLine(type)

    val separators = listOf("-", "－", "—", "–")
    for (separator in separators) {
        val index = clean.indexOf(separator)
        if (index >= 0 && index < clean.lastIndex) {
            return clean.substring(0, index + separator.length) +
                "\n" +
                clean.substring(index + separator.length).trim()
        }
    }

    return clean
}
private fun selectAvatarSource(
    context: android.content.Context,
    name: String,
    tagName: String,
    type: String,
    avatarUrl: String?,
    fallback: Int
): Any {
    val icon = avatarUrl.orEmpty().trim()

    // 1. 配置/插件已经保存了具体头像，则优先使用
    if (
        icon.startsWith("http://") ||
        icon.startsWith("https://") ||
        icon.startsWith("file://") ||
        icon.startsWith("content://") ||
        icon.startsWith("android.resource://") ||
        icon.startsWith("data:image") ||
        icon.startsWith("/")
    ) {
        return icon
    }

    // 2. 没有插件头像，再按本地男女老幼/旁白/音效规则匹配
    val source = oneLine("$name $tagName $type").lowercase()

    val resName = when {
        source.contains("在线音效") ||
            source.contains("网络音效") ||
            source.contains("online", ignoreCase = true) -> {
            "tts_avatar_online_effect"
        }

        source.contains("本地音效") ||
            source.contains("本地") ||
            source.contains("local", ignoreCase = true) -> {
            "tts_avatar_local_effect"
        }

        source.contains("旁白") ||
            source.contains("narration", ignoreCase = true) ||
            source.contains("narrator", ignoreCase = true) ||
            source.contains("解说") ||
            source.contains("讲述") -> {
            "tts_avatar_narration"
        }

        source.contains("女童") -> "tts_avatar_female_child"
        source.contains("男童") -> "tts_avatar_male_child"

        source.contains("少女") -> "tts_avatar_teen_girl"
        source.contains("少年") -> "tts_avatar_teen_boy"

        source.contains("女青年") || source.contains("女青") -> "tts_avatar_female_young"
        source.contains("男青年") || source.contains("男青") -> "tts_avatar_male_young"

        source.contains("女中年") || source.contains("女中") -> "tts_avatar_female_middle"
        source.contains("男中年") || source.contains("男中") -> "tts_avatar_male_middle"

        source.contains("女老年") || source.contains("女老") -> "tts_avatar_female_old"
        source.contains("男老年") || source.contains("男老") -> "tts_avatar_male_old"

        source.contains("女") || source.contains("female", ignoreCase = true) -> "tts_avatar_female_young"
        source.contains("男") || source.contains("male", ignoreCase = true) -> "tts_avatar_male_young"

        else -> null
    }

    if (resName != null) {
        val resId = context.resources.getIdentifier(
            resName,
            "drawable",
            context.packageName
        )

        if (resId != 0) return resId
    }

    return fallback
}
