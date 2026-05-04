package com.github.jing332.tts_server_android.compose.codeeditor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.MotionEvent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.jing332.common.utils.longToast
import com.github.jing332.compose.ComposeExtensions.clickableRipple
import com.github.jing332.compose.widgets.AppTooltip
import com.github.jing332.compose.widgets.CheckedMenuItem
import com.github.jing332.compose.widgets.LongClickIconButton
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.conf.CodeEditorConfig
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.FilePickerActivity
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import com.github.jing332.common.utils.ClipboardUtils
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch

fun CodeEditor.string(): String {
    return runCatching {
        val content = this.text
        val lineCount = content.javaClass.methods
            .firstOrNull { it.name == "getLineCount" && it.parameterTypes.isEmpty() }
            ?.invoke(content) as? Int ?: return@runCatching content.toString()

        val getLineStringMethod = content.javaClass.methods
            .firstOrNull { it.name == "getLineString" && it.parameterTypes.size == 1 }

        val getLineMethod = content.javaClass.methods
            .firstOrNull { it.name == "getLine" && it.parameterTypes.size == 1 }

        val sb = StringBuilder()

        for (line in 0 until lineCount) {
            if (line > 0) sb.append('\n')

            val lineText = when {
                getLineStringMethod != null -> {
                    getLineStringMethod.invoke(content, line)?.toString().orEmpty()
                }

                getLineMethod != null -> {
                    getLineMethod.invoke(content, line)?.toString().orEmpty()
                }

                else -> {
                    return@runCatching content.toString()
                }
            }

            sb.append(lineText)
        }

        sb.toString()
    }.getOrElse {
        this.text.toString()
    }
}

private data class FindMatch(
    val start: Int,
    val end: Int
)

private fun findAllMatches(
    text: String,
    keyword: String,
    ignoreCase: Boolean = false
): List<FindMatch> {
    if (keyword.isEmpty()) return emptyList()

    val result = mutableListOf<FindMatch>()
    var startIndex = 0

    while (startIndex <= text.length) {
        val index = text.indexOf(
            string = keyword,
            startIndex = startIndex,
            ignoreCase = ignoreCase
        )

        if (index < 0) break

        result.add(
            FindMatch(
                start = index,
                end = index + keyword.length
            )
        )

        startIndex = index + keyword.length.coerceAtLeast(1)
    }

    return result
}

private fun normalizeFindIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0

    var result = index % size
    if (result < 0) result += size
    return result
}

private fun offsetToLineColumn(text: String, offset: Int): Pair<Int, Int> {
    val safeOffset = offset.coerceIn(0, text.length)

    var line = 0
    var column = 0

    for (i in 0 until safeOffset) {
        if (text[i] == '\n') {
            line++
            column = 0
        } else {
            column++
        }
    }

    return line to column
}

private fun CodeEditor.selectOffsetRange(start: Int, end: Int) {
    val editor = this

    editor.post {
        val code = editor.string()
        val safeStart = start.coerceIn(0, code.length)
        val safeEnd = end.coerceIn(safeStart, code.length)

        val startPos = offsetToLineColumn(code, safeStart)
        val endPos = offsetToLineColumn(code, safeEnd)

        val startLine = startPos.first
        val startColumn = startPos.second
        val endLine = endPos.first
        val endColumn = endPos.second

        runCatching {
            editor.requestFocus()

            val methods = editor.javaClass.methods

            methods.firstOrNull {
                it.name == "jumpToLine" && it.parameterTypes.size == 1
            }?.invoke(editor, startLine)

            methods.firstOrNull {
                it.name == "jumpToLine" && it.parameterTypes.size == 2
            }?.invoke(editor, startLine, startColumn)

            val setSelectionRegion5 = methods.firstOrNull {
                it.name == "setSelectionRegion" && it.parameterTypes.size == 5
            }

            val setSelectionRegion4 = methods.firstOrNull {
                it.name == "setSelectionRegion" && it.parameterTypes.size == 4
            }

            val setSelection3 = methods.firstOrNull {
                it.name == "setSelection" && it.parameterTypes.size == 3
            }

            val setSelection2 = methods.firstOrNull {
                it.name == "setSelection" && it.parameterTypes.size == 2
            }

            when {
                setSelectionRegion5 != null -> {
                    setSelectionRegion5.invoke(
                        editor,
                        startLine,
                        startColumn,
                        endLine,
                        endColumn,
                        0
                    )
                }

                setSelectionRegion4 != null -> {
                    setSelectionRegion4.invoke(
                        editor,
                        startLine,
                        startColumn,
                        endLine,
                        endColumn
                    )
                }

                setSelection3 != null -> {
                    setSelection3.invoke(
                        editor,
                        startLine,
                        startColumn,
                        true
                    )
                }

                setSelection2 != null -> {
                    setSelection2.invoke(
                        editor,
                        startLine,
                        startColumn
                    )
                }
            }

            fun ensureVisible() {
                methods.firstOrNull {
                    it.name == "ensureSelectionVisible" && it.parameterTypes.isEmpty()
                }?.invoke(editor)

                methods.firstOrNull {
                    it.name == "ensurePositionVisible" && it.parameterTypes.size == 2
                }?.invoke(editor, startLine, startColumn)

                methods.firstOrNull {
                    it.name == "ensurePositionVisible" && it.parameterTypes.size == 3
                }?.invoke(editor, startLine, startColumn, true)

                methods.firstOrNull {
                    it.name == "ensurePositionVisible" && it.parameterTypes.size == 4
                }?.invoke(editor, startLine, startColumn, endLine, endColumn)

                editor.invalidate()
            }

            ensureVisible()

            editor.postDelayed({
                runCatching {
                    ensureVisible()
                }
            }, 120)
        }.onFailure {
            runCatching {
                val methods = editor.javaClass.methods

                methods.firstOrNull {
                    it.name == "setSelection" && it.parameterTypes.size == 2
                }?.invoke(editor, startLine, startColumn)

                editor.invalidate()
            }
        }
    }
}

private data class CodeBlockRange(
    val start: Int,
    val end: Int,
    val startLine: Int,
    val endLine: Int,
    val type: String
)

private data class FoldedCodeBlock(
    val id: String,
    val originalText: String,
)

private val foldedBlockMarkerRegex = Regex(
    pattern = """/\*\s*⯈\s*folded-code-block:([a-zA-Z0-9_]+)\s*\*/"""
)

private fun String.lineRangeAroundOffset(offset: Int): IntRange {
    val safeOffset = offset.coerceIn(0, length)
    val start = lastIndexOf('\n', safeOffset - 1).let {
        if (it < 0) 0 else it + 1
    }
    val end = indexOf('\n', safeOffset).let {
        if (it < 0) length else it + 1
    }
    return start until end
}

private fun shortenFoldPreview(line: String, maxLength: Int = 120): String {
    val trimmed = line.trimEnd()
    return if (trimmed.length <= maxLength) {
        trimmed
    } else {
        trimmed.take(maxLength) + " …"
    }
}
private fun lineColumnToOffset(text: String, line: Int, column: Int): Int {
    if (line <= 0) return column.coerceIn(0, text.length)

    var currentLine = 0
    var offset = 0

    while (offset < text.length && currentLine < line) {
        if (text[offset] == '\n') {
            currentLine++
        }
        offset++
    }

    return (offset + column).coerceIn(0, text.length)
}

private fun CodeEditor.currentCursorOffset(): Int {
    val editor = this

    return runCatching {
        val cursor = editor.javaClass.methods
            .firstOrNull { it.name == "getCursor" && it.parameterTypes.isEmpty() }
            ?.invoke(editor)

        if (cursor != null) {
            val methods = cursor.javaClass.methods

            val line = (
                    methods.firstOrNull { it.name == "getLeftLine" && it.parameterTypes.isEmpty() }
                        ?.invoke(cursor)
                        ?: methods.firstOrNull { it.name == "getLine" && it.parameterTypes.isEmpty() }
                            ?.invoke(cursor)
                    ) as? Int ?: 0

            val column = (
                    methods.firstOrNull { it.name == "getLeftColumn" && it.parameterTypes.isEmpty() }
                        ?.invoke(cursor)
                        ?: methods.firstOrNull { it.name == "getColumn" && it.parameterTypes.isEmpty() }
                            ?.invoke(cursor)
                    ) as? Int ?: 0

            lineColumnToOffset(editor.string(), line, column)
        } else {
            0
        }
    }.getOrDefault(0)
}

private fun findJsCodeBlockAroundOffset(
    text: String,
    cursorOffset: Int,
): CodeBlockRange? {
    if (text.isBlank()) return null

    data class Token(
        val ch: Char,
        val offset: Int,
        val line: Int
    )

    val pairs = mutableListOf<Pair<Token, Token>>()
    val stack = ArrayDeque<Token>()

    var i = 0
    var line = 0

    var inLineComment = false
    var inBlockComment = false
    var inString: Char? = null
    var escape = false

    while (i < text.length) {
        val ch = text[i]
        val next = text.getOrNull(i + 1)

        if (ch == '\n') {
            line++
            inLineComment = false
            escape = false
            i++
            continue
        }

        if (inLineComment) {
            i++
            continue
        }

        if (inBlockComment) {
            if (ch == '*' && next == '/') {
                inBlockComment = false
                i += 2
            } else {
                i++
            }
            continue
        }

        val currentString = inString
        if (currentString != null) {
            if (escape) {
                escape = false
            } else if (ch == '\\') {
                escape = true
            } else if (ch == currentString) {
                inString = null
            }
            i++
            continue
        }

        if (ch == '/' && next == '/') {
            inLineComment = true
            i += 2
            continue
        }

        if (ch == '/' && next == '*') {
            inBlockComment = true
            i += 2
            continue
        }

        if (ch == '"' || ch == '\'' || ch == '`') {
            inString = ch
            i++
            continue
        }

        when (ch) {
            '{', '[' -> {
                stack.addLast(Token(ch, i, line))
            }

            '}', ']' -> {
                val expectedOpen = if (ch == '}') '{' else '['

                while (stack.isNotEmpty()) {
                    val open = stack.removeLast()
                    if (open.ch == expectedOpen) {
                        val close = Token(ch, i, line)
                        if (close.line > open.line) {
                            pairs += open to close
                        }
                        break
                    }
                }
            }
        }

        i++
    }

    val safeOffset = cursorOffset.coerceIn(0, text.length)

    val matched = pairs
        .filter { (open, close) ->
            open.offset <= safeOffset && safeOffset <= close.offset
        }
        .minByOrNull { (open, close) ->
            close.offset - open.offset
        }
        ?: return null

    val open = matched.first
    val close = matched.second

    val startLineOffset = text.lastIndexOf('\n', open.offset).let {
        if (it < 0) 0 else it + 1
    }

    val endLineOffset = text.indexOf('\n', close.offset).let {
        if (it < 0) text.length else it + 1
    }

    val type = when (open.ch) {
        '{' -> "函数/对象块"
        '[' -> "数组块"
        else -> "代码块"
    }

    return CodeBlockRange(
        start = startLineOffset,
        end = endLineOffset,
        startLine = open.line,
        endLine = close.line,
        type = type
    )
}


private fun findAllJsFunctionCodeBlocks(
    text: String,
    minLines: Int = 4,
): List<CodeBlockRange> {
    if (text.isBlank()) return emptyList()

    data class Token(
        val ch: Char,
        val offset: Int,
        val line: Int
    )

    val pairs = mutableListOf<Pair<Token, Token>>()
    val stack = ArrayDeque<Token>()

    var i = 0
    var line = 0

    var inLineComment = false
    var inBlockComment = false
    var inString: Char? = null
    var escape = false

    while (i < text.length) {
        val ch = text[i]
        val next = text.getOrNull(i + 1)

        if (ch == '\n') {
            line++
            inLineComment = false
            escape = false
            i++
            continue
        }

        if (inLineComment) {
            i++
            continue
        }

        if (inBlockComment) {
            if (ch == '*' && next == '/') {
                inBlockComment = false
                i += 2
            } else {
                i++
            }
            continue
        }

        val currentString = inString
        if (currentString != null) {
            if (escape) {
                escape = false
            } else if (ch == '\\') {
                escape = true
            } else if (ch == currentString) {
                inString = null
            }
            i++
            continue
        }

        if (ch == '/' && next == '/') {
            inLineComment = true
            i += 2
            continue
        }

        if (ch == '/' && next == '*') {
            inBlockComment = true
            i += 2
            continue
        }

        if (ch == '"' || ch == '\'' || ch == '`') {
            inString = ch
            i++
            continue
        }

        when (ch) {
            '{' -> {
                stack.addLast(Token(ch, i, line))
            }

            '}' -> {
                while (stack.isNotEmpty()) {
                    val open = stack.removeLast()
                    if (open.ch == '{') {
                        val close = Token(ch, i, line)
                        if (close.line - open.line >= minLines) {
                            pairs += open to close
                        }
                        break
                    }
                }
            }
        }

        i++
    }

    val blocks = pairs.mapNotNull { pair ->
        val open = pair.first
        val close = pair.second

        val startLineOffset = text.lastIndexOf('\n', open.offset).let {
            if (it < 0) 0 else it + 1
        }

        val endLineOffset = text.indexOf('\n', close.offset).let {
            if (it < 0) text.length else it + 1
        }

        val lineEndOffset = text.indexOf('\n', open.offset).let {
            if (it < 0) text.length else it
        }

        val header = text.substring(startLineOffset, open.offset.coerceAtMost(lineEndOffset))
            .trim()

        val looksLikeFunction =
            header.contains("function", ignoreCase = true) ||
                    header.contains("=>") ||
                    header.startsWith("async ", ignoreCase = true)

        if (!looksLikeFunction) {
            return@mapNotNull null
        }

        CodeBlockRange(
            start = startLineOffset,
            end = endLineOffset,
            startLine = open.line,
            endLine = close.line,
            type = "函数块"
        )
    }

    // 避免父子函数同时折叠造成占位嵌套：只保留最外层函数块
    val sortedBlocks = blocks.sortedWith(
        compareBy<CodeBlockRange> { it.start }.thenByDescending { it.end }
    )

    return sortedBlocks.filterIndexed { index, block ->
        sortedBlocks.take(index).none { parent ->
            parent.start <= block.start && block.end <= parent.end
        }
    }
}
@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CodeEditorScreen(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onLongClickSave: () -> Unit = {},
    onUpdate: (CodeEditor) -> Unit,
    onSaveFile: (() -> Pair<String, ByteArray>)?,

    onDebug: () -> Unit,
    onRemoteAction: (name: String, body: ByteArray?) -> Unit = { _, _ -> },

    vm: CodeEditorViewModel = viewModel(),

    debugIconContent: @Composable () -> Unit = {},
    onLongClickMore: () -> Unit = {},
    onLongClickMoreLabel: String? = null,
    actions: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
) {
    var codeEditor by remember { mutableStateOf<CodeEditor?>(null) }

    var showThemeDialog by remember { mutableStateOf(false) }
    if (showThemeDialog) {
        ThemeSettingsDialog { showThemeDialog = false }
    }

    var showRemoteSyncDialog by remember { mutableStateOf(false) }
    if (showRemoteSyncDialog) {
        RemoteSyncSettings { showRemoteSyncDialog = false }
    }

    var showFindReplaceBar by remember { mutableStateOf(false) }
    var findKeyword by remember { mutableStateOf("") }
    var replaceKeyword by remember { mutableStateOf("") }
    var currentFindIndex by remember { mutableIntStateOf(-1) }
    var editorContentVersion by remember { mutableIntStateOf(0) }
    val foldedBlocks = remember { mutableMapOf<String, FoldedCodeBlock>() }
    var nextFoldId by remember { mutableIntStateOf(1) }

    val fileSaver =
        rememberLauncherForActivityResult(AppActivityResultContracts.filePickerActivity()) {
        }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun jumpToFindMatch(targetIndex: Int) {
        val editor = codeEditor ?: return
        val matches = findAllMatches(editor.string(), findKeyword)

        if (matches.isEmpty()) {
            context.longToast("未找到匹配内容")
            currentFindIndex = -1
            return
        }

        val newIndex = normalizeFindIndex(targetIndex, matches.size)
        currentFindIndex = newIndex

        val match = matches[newIndex]
        editor.selectOffsetRange(match.start, match.end)

        val line = offsetToLineColumn(editor.string(), match.start).first + 1
        context.longToast("已定位到第 ${line} 行")
    }

    fun replaceCurrentMatch() {
        val editor = codeEditor ?: return
        val oldText = editor.string()
        val matches = findAllMatches(oldText, findKeyword)

        if (matches.isEmpty()) {
            context.longToast("未找到匹配内容")
            currentFindIndex = -1
            return
        }

        val index = currentFindIndex.coerceIn(0, matches.lastIndex)
        val match = matches[index]

        val newText =
            oldText.substring(0, match.start) +
                    replaceKeyword +
                    oldText.substring(match.end)

        editor.setText(newText)
        editorContentVersion++

        val replacedEnd = match.start + replaceKeyword.length
        editor.selectOffsetRange(match.start, replacedEnd)

        val newMatches = findAllMatches(newText, findKeyword)
        currentFindIndex =
            if (newMatches.isEmpty()) -1 else index.coerceAtMost(newMatches.lastIndex)
    }

    fun replaceAllMatches() {
        val editor = codeEditor ?: return

        if (findKeyword.isEmpty()) {
            context.longToast("查找内容不可为空")
            return
        }

        val oldText = editor.string()
        val matches = findAllMatches(oldText, findKeyword)

        if (matches.isEmpty()) {
            context.longToast("未找到匹配内容")
            currentFindIndex = -1
            return
        }

        editor.setText(oldText.replace(findKeyword, replaceKeyword))
        editorContentVersion++
        currentFindIndex = -1
        context.longToast("已替换 ${matches.size} 处")
    }

    fun selectCurrentCodeBlock() {
        val editor = codeEditor ?: return

        val code = editor.string()
        val range = findJsCodeBlockAroundOffset(
            text = code,
            cursorOffset = editor.currentCursorOffset()
        )

        if (range == null) {
            context.longToast("未找到当前函数/数组/对象块")
            return
        }

        editor.selectOffsetRange(range.start, range.end)
        context.longToast("已选中${range.type}：第 ${range.startLine + 1} - ${range.endLine + 1} 行")
    }

    fun copyCurrentCodeBlock() {
        val editor = codeEditor ?: return

        val code = editor.string()
        val range = findJsCodeBlockAroundOffset(
            text = code,
            cursorOffset = editor.currentCursorOffset()
        )

        if (range == null) {
            context.longToast("未找到当前函数/数组/对象块")
            return
        }

        val text = code.substring(range.start, range.end)

        if (ClipboardUtils.copyText(text)) {
            context.longToast("已复制${range.type}：第 ${range.startLine + 1} - ${range.endLine + 1} 行")
        }
    }

    fun deleteCurrentCodeBlock() {
        val editor = codeEditor ?: return

        val code = editor.string()
        val range = findJsCodeBlockAroundOffset(
            text = code,
            cursorOffset = editor.currentCursorOffset()
        )

        if (range == null) {
            context.longToast("未找到当前函数/数组/对象块")
            return
        }

        val newCode = code.removeRange(range.start, range.end)
        editor.setText(newCode)
        editorContentVersion++

        val safeOffset = range.start.coerceIn(0, newCode.length)
        editor.selectOffsetRange(safeOffset, safeOffset)

        context.longToast("已删除${range.type}：第 ${range.startLine + 1} - ${range.endLine + 1} 行")
    }
    fun expandFoldedBlockAtCursor(): Boolean {
        val editor = codeEditor ?: return false
        val code = editor.string()
        val cursorOffset = editor.currentCursorOffset()
        val lineRange = code.lineRangeAroundOffset(cursorOffset)
        val lineText = code.substring(lineRange)

        val match = foldedBlockMarkerRegex.find(lineText) ?: return false
        val id = match.groupValues.getOrNull(1).orEmpty()
        val folded = foldedBlocks[id] ?: return false

        val newCode = code.replaceRange(lineRange, folded.originalText)

        editor.setText(newCode)
        editorContentVersion++
        foldedBlocks.remove(id)

        editor.selectOffsetRange(lineRange.first, lineRange.first)
        context.longToast("已展开代码块")
        return true
    }

    fun expandAllFoldedBlocks(showToast: Boolean = true): Boolean {
        val editor = codeEditor ?: return false
        var code = editor.string()

        val matches = foldedBlockMarkerRegex.findAll(code).toList()
        if (matches.isEmpty()) return false

        var changed = false

        matches.asReversed().forEach { match ->
            val id = match.groupValues.getOrNull(1).orEmpty()
            val folded = foldedBlocks[id] ?: return@forEach

            val lineRange = code.lineRangeAroundOffset(match.range.first)
            code = code.replaceRange(lineRange, folded.originalText)
            foldedBlocks.remove(id)
            changed = true
        }

        if (changed) {
            editor.setText(code)
            editorContentVersion++
            if (showToast) {
                context.longToast("已展开全部折叠代码块")
            }
        }

        return changed
    }

    fun foldCurrentCodeBlock() {
        val editor = codeEditor ?: return

        // 如果当前光标已经在折叠占位行上，点一次就是展开
        if (expandFoldedBlockAtCursor()) return

        val code = editor.string()
        val range = findJsCodeBlockAroundOffset(
            text = code,
            cursorOffset = editor.currentCursorOffset()
        )

        if (range == null) {
            context.longToast("未找到当前函数/数组/对象块")
            return
        }

        if (range.endLine <= range.startLine) {
            context.longToast("单行代码块不需要折叠")
            return
        }

        val original = code.substring(range.start, range.end)
        val firstLine = original.lineSequence().firstOrNull().orEmpty()
        val preview = shortenFoldPreview(firstLine)
        val lineCount = original.lineSequence().count().coerceAtLeast(1)

        val id = "fold_${System.currentTimeMillis()}_${nextFoldId++}"
        foldedBlocks[id] = FoldedCodeBlock(
            id = id,
            originalText = original
        )

        val placeholder = "$preview /* ⯈ folded-code-block:$id */  // folded $lineCount lines\n"

        val newCode = code.replaceRange(range.start, range.end, placeholder)

        editor.setText(newCode)
        editorContentVersion++
        editor.selectOffsetRange(range.start, range.start + placeholder.length)

        context.longToast("已折叠${range.type}：第 ${range.startLine + 1} - ${range.endLine + 1} 行")
    }

    fun toggleFoldAtCursor() {
        if (!expandFoldedBlockAtCursor()) {
            foldCurrentCodeBlock()
        }
    }


    fun foldAllFunctionBlocks() {
        val editor = codeEditor ?: return

        // 先展开已有软折叠，避免重复折叠或嵌套占位
        expandAllFoldedBlocks(showToast = false)

        val code = editor.string()
        val ranges = findAllJsFunctionCodeBlocks(code)

        if (ranges.isEmpty()) {
            context.longToast("未找到可折叠函数块")
            return
        }

        var currentCode = code
        var count = 0

        ranges
            .sortedByDescending { it.start }
            .forEach { range ->
                if (range.start !in 0..currentCode.length || range.end !in 0..currentCode.length) {
                    return@forEach
                }

                val original = currentCode.substring(range.start, range.end)
                if (foldedBlockMarkerRegex.containsMatchIn(original)) {
                    return@forEach
                }

                val firstLine = original.lineSequence().firstOrNull().orEmpty()
                val preview = shortenFoldPreview(firstLine)
                val lineCount = original.lineSequence().count().coerceAtLeast(1)

                val id = "fold_${System.currentTimeMillis()}_${nextFoldId++}"
                foldedBlocks[id] = FoldedCodeBlock(
                    id = id,
                    originalText = original
                )

                val placeholder =
                    "$preview /* ⯈ folded-code-block:$id */  // folded $lineCount lines\n"

                currentCode = currentCode.replaceRange(
                    range.start,
                    range.end,
                    placeholder
                )

                count++
            }

        if (count <= 0) {
            context.longToast("没有新的函数块可折叠")
            return
        }

        editor.setText(currentCode)
        editorContentVersion++

        context.longToast("已折叠 $count 个函数块")
    }

    BackHandler {
        expandAllFoldedBlocks(showToast = false)
        onBack()
    }

    LaunchedEffect(vm) {
        if (CodeEditorConfig.isRemoteSyncEnabled.value) {
            vm.startSyncServer(
                port = CodeEditorConfig.remoteSyncPort.value,
                onPush = {
                    foldedBlocks.clear()
                    codeEditor?.setText(it)
                    editorContentVersion++
                },
                onPull = {
                    expandAllFoldedBlocks(showToast = false)
                    codeEditor?.string().orEmpty()
                },
                onDebug = onDebug,
                onAction = onRemoteAction
            )
        }

        scope.launch {
            vm.error.collect {
                when (it) {
                    Error.Empty -> {}

                    is Error.Other -> {
                        context.displayErrorDialog(t = it.e)
                    }

                    Error.PortConflict -> {
                        context.longToast("RemoteSync: port conflict!")
                    }

                    is Error.Socket -> {
                        context.longToast("RemoteSync: ${it.message}")
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(
                        onClick = {
                            expandAllFoldedBlocks(showToast = false)
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            expandAllFoldedBlocks()
                            onDebug()
                        }
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(id = R.string.debug)
                        )
                        debugIconContent()
                    }

                    LongClickIconButton(
                        onClick = { codeEditor?.undo() },
                        onLongClickLabel = stringResource(R.string.redo),
                        onLongClick = { codeEditor?.redo() }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(id = R.string.undo)
                        )
                    }

                    AppTooltip(tooltip = stringResource(R.string.format_code)) {
                        IconButton(
                            onClick = {
                                codeEditor?.let {
                                    expandAllFoldedBlocks()
                                    val newCode = vm.formatCode(it.string())
                                    it.setText(newCode)
                                    editorContentVersion++
                                }
                            }
                        ) {
                            Icon(Icons.Default.Code, stringResource(id = R.string.format_code))
                        }
                    }

                    LongClickIconButton(
                        onClick = {
                            expandAllFoldedBlocks()
                            onSave()
                        },
                        onLongClick = {
                            expandAllFoldedBlocks()
                            onLongClickSave()
                        }
                    ) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(id = R.string.save)
                        )
                    }

                    var showOptions by remember { mutableStateOf(false) }

                    LongClickIconButton(
                        onClick = { showOptions = true },
                        onLongClick = onLongClickMore,
                        onLongClickLabel = onLongClickMoreLabel
                    ) {
                        Icon(Icons.Default.MoreVert, stringResource(id = R.string.more_options))

                        DropdownMenu(
                            expanded = showOptions,
                            onDismissRequest = { showOptions = false }
                        ) {
                            if (onSaveFile != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(id = R.string.save_as_file)) },
                                    onClick = {
                                        expandAllFoldedBlocks(showToast = false)

                                        onSaveFile.invoke().let {
                                            fileSaver.launch(
                                                FilePickerActivity.RequestSaveFile(
                                                    fileName = it.first,
                                                    fileBytes = it.second
                                                )
                                            )
                                        }

                                        showOptions = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.InsertDriveFile,
                                            null
                                        )
                                    }
                                )


                            }

                            var syncEnabled by remember { CodeEditorConfig.isRemoteSyncEnabled }
                            CheckedMenuItem(
                                text = { Text(stringResource(id = R.string.remote_sync_service)) },
                                checked = syncEnabled,
                                onClick = { showRemoteSyncDialog = true },
                                onClickCheckBox = { syncEnabled = it },
                                leadingIcon = {
                                    Icon(Icons.Default.SettingsRemote, null)
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.theme)) },
                                onClick = {
                                    showOptions = false
                                    showThemeDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.ColorLens, null) }
                            )

                            DropdownMenuItem(
                                text = { Text("查找 / 替换") },
                                onClick = {
                                    showOptions = false
                                    showFindReplaceBar = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("折叠 / 展开当前代码块") },
                                onClick = {
                                    showOptions = false
                                    toggleFoldAtCursor()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("折叠所有函数") },
                                onClick = {
                                    showOptions = false
                                    foldAllFunctionBlocks()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("展开全部折叠") },
                                onClick = {
                                    showOptions = false
                                    if (!expandAllFoldedBlocks()) {
                                        context.longToast("没有折叠的代码块")
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("选中当前代码块") },
                                onClick = {
                                    showOptions = false
                                    selectCurrentCodeBlock()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("复制当前代码块") },
                                onClick = {
                                    showOptions = false
                                    copyCurrentCodeBlock()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("删除当前代码块") },
                                onClick = {
                                    showOptions = false
                                    deleteCurrentCodeBlock()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Code, null)
                                }
                            )
                            var wordWrap by remember { CodeEditorConfig.isWordWrapEnabled }
                            CheckedMenuItem(
                                text = { Text(stringResource(id = R.string.word_wrap)) },
                                checked = wordWrap,
                                onClick = { wordWrap = it },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Default.WrapText, null)
                                }
                            )

                            actions { showOptions = false }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        val theme by remember { CodeEditorConfig.theme }
        LaunchedEffect(codeEditor, theme) {
            codeEditor?.helper()?.setTheme(theme)
        }

        val wordWrap by remember { CodeEditorConfig.isWordWrapEnabled }
        LaunchedEffect(codeEditor, wordWrap) {
            codeEditor?.isWordwrap = wordWrap
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CodeEditor(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onUpdate = {
                    it.isUndoEnabled = true

                    val leftFoldTouchWidth = 56f * context.resources.displayMetrics.density

                    it.setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_UP && event.x <= leftFoldTouchWidth) {
                            view.performClick()

                            it.postDelayed({
                                toggleFoldAtCursor()
                            }, 80L)
                        }

                        false
                    }


                    it.text.addContentListener(
                        object : ContentListener {
                            override fun beforeReplace(content: Content) {
                            }

                            override fun afterInsert(
                                content: Content,
                                startLine: Int,
                                startColumn: Int,
                                endLine: Int,
                                endColumn: Int,
                                insertedContent: CharSequence,
                            ) {
                                editorContentVersion++
                            }

                            override fun afterDelete(
                                content: Content,
                                startLine: Int,
                                startColumn: Int,
                                endLine: Int,
                                endColumn: Int,
                                deletedContent: CharSequence,
                            ) {
                                editorContentVersion++
                            }
                        }
                    )

                    codeEditor = it
                    onUpdate(it)
                }
            )

            if (showFindReplaceBar) {
                val matchCount = remember(editorContentVersion, findKeyword, codeEditor) {
                    findAllMatches(
                        text = codeEditor?.string().orEmpty(),
                        keyword = findKeyword
                    ).size
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BasicTextField(
                            value = findKeyword,
                            onValueChange = { newValue ->
                                findKeyword = newValue
                                currentFindIndex = -1
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (findKeyword.isEmpty()) {
                                        Text(
                                            text = "查找",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        TextButton(
                            onClick = {
                                showFindReplaceBar = false
                            },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text("×")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        BasicTextField(
                            value = replaceKeyword,
                            onValueChange = { newValue ->
                                replaceKeyword = newValue
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (replaceKeyword.isEmpty()) {
                                        Text(
                                            text = "替换为",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Text(
                            text = if (matchCount > 0 && currentFindIndex >= 0) {
                                "${currentFindIndex + 1}/$matchCount"
                            } else {
                                "0/$matchCount"
                            },
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = {
                                val targetIndex =
                                    if (currentFindIndex < 0) matchCount - 1 else currentFindIndex - 1
                                jumpToFindMatch(targetIndex)
                            }
                        ) {
                            Text("上个")
                        }

                        TextButton(
                            onClick = {
                                val targetIndex =
                                    if (currentFindIndex < 0) 0 else currentFindIndex + 1
                                jumpToFindMatch(targetIndex)
                            }
                        ) {
                            Text("下个")
                        }

                        TextButton(
                            onClick = {
                                replaceCurrentMatch()
                            }
                        ) {
                            Text("替换")
                        }

                        TextButton(
                            onClick = {
                                replaceAllMatches()
                            }
                        ) {
                            Text("全部")
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp)
            }

            val symbolMap = remember {
                linkedMapOf(
                    "\t" to "TAB",
                    "=" to "=",
                    ">" to ">",
                    "{" to "{",
                    "}" to "}",
                    "(" to "(",
                    ")" to ")",
                    "," to ",",
                    "." to ".",
                    ";" to ";",
                    "'" to "'",
                    "\"" to "\"",
                    "?" to "?",
                    "+" to "+",
                    "-" to "-",
                    "*" to "*",
                    "/" to "/",
                )
            }

            HorizontalDivider(thickness = 1.dp)
            LazyRow(
                Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding()
                    .then(
                        if (WindowInsets.isImeVisible) Modifier else Modifier.navigationBarsPadding()
                    )
            ) {
                items(symbolMap.toList()) {
                    Box(
                        Modifier
                            .clickableRipple {
                                codeEditor?.let { editor ->
                                    val text = it.first
                                    if (editor.isEditable) {
                                        if ("\t" == text && editor.snippetController.isInSnippet()) {
                                            editor.snippetController.shiftToNextTabStop()
                                        } else {
                                            editor.insertText(text, 1)
                                        }
                                    }
                                }
                            }
                    ) {
                        Text(
                            text = it.second,
                            Modifier
                                .minimumInteractiveComponentSize()
                                .align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}