#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import re
import shutil
import sys
from pathlib import Path

HELPER = r'''
// ===================== JREAD_MARKER_PATCH_V1：无Web整章Marker接收补丁 =====================
var JREAD_MARKER_PATCH_V1 = 1;

// 0=返回空白文本，兼容性最好；1=返回空数组，更干净但部分环境可能不支持
var JREAD_MARKER_SILENT_RETURNS_EMPTY = 0;

function __jreadMarkerLog(msg) {
    try { console.log(String(msg)); } catch (e) {}
}

function __jreadMarkerSilent() {
    if (JREAD_MARKER_SILENT_RETURNS_EMPTY === 1) {
        return { handled: true, result: [] };
    }
    return { handled: true, result: [{ text: " ", tag: "default" }] };
}

function __jreadMarkerSafeFilePart(s) {
    return String(s || "").replace(/[^A-Za-z0-9_\-]/g, "_").slice(0, 80);
}

function __jreadMarkerReadText(fileName, fallback) {
    try {
        var raw = ttsrv.readTxtFile(fileName);
        if (raw === null || typeof raw === "undefined") return fallback;
        return String(raw);
    } catch (e) {
        return fallback;
    }
}

function __jreadMarkerWriteText(fileName, text) {
    try {
        ttsrv.writeTxtFile(fileName, String(text));
        return true;
    } catch (e) {
        __jreadMarkerLog("【JREAD无Web】写入失败 " + fileName + " | " + e);
        return false;
    }
}

function __jreadMarkerReadJson(fileName, fallback) {
    try {
        var raw = __jreadMarkerReadText(fileName, "");
        if (!raw || String(raw).trim() === "") return fallback;
        return JSON.parse(String(raw));
    } catch (e) {
        return fallback;
    }
}

function __jreadMarkerExtractBlock(rawText, openTag, closeTag) {
    rawText = String(rawText || "");
    var start = rawText.indexOf(openTag);
    if (start < 0) return "";
    start += openTag.length;
    var end = rawText.indexOf(closeTag, start);
    if (end < 0) return "";
    return rawText.substring(start, end).trim();
}

function __jreadMarkerDecodeBase64(s) {
    s = String(s || "");
    try {
        var bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
        return String(new java.lang.String(bytes, "UTF-8"));
    } catch (e1) {}

    try {
        var bytes2 = java.util.Base64.getDecoder().decode(s);
        return String(new java.lang.String(bytes2, "UTF-8"));
    } catch (e2) {}

    return null;
}

function __jreadMarkerDecodeUrl(s) {
    s = String(s || "");
    try {
        return String(java.net.URLDecoder.decode(s, "UTF-8"));
    } catch (e) {
        return null;
    }
}

function __jreadMarkerDecodePayload(payload, encoding) {
    payload = String(payload || "");
    encoding = String(encoding || "").toLowerCase();

    var decoded = null;

    if (encoding === "base64") {
        decoded = __jreadMarkerDecodeBase64(payload);
        if (decoded !== null) return decoded;
    }

    if (encoding === "url" || encoding === "uri" || encoding === "urlencode") {
        decoded = __jreadMarkerDecodeUrl(payload);
        if (decoded !== null) return decoded;
    }

    // encoding 缺失时：先试 base64，再试 url，最后原文兜底
    decoded = __jreadMarkerDecodeBase64(payload);
    if (decoded !== null && decoded.indexOf("{") !== -1) return decoded;

    decoded = __jreadMarkerDecodeUrl(payload);
    if (decoded !== null && decoded.indexOf("{") !== -1) return decoded;

    return payload;
}

function __jreadMarkerNormalizeBookName(name) {
    return String(name || "").replace(/[\u200B-\u200D\uFEFF]/g, "").trim();
}

function __jreadMarkerHandleCtxChunk(rawText) {
    var block = __jreadMarkerExtractBlock(
        rawText,
        "[[JREAD_CTX_CHUNK_V1]]",
        "[[/JREAD_CTX_CHUNK_V1]]"
    );

    if (!block) return null;

    try {
        var chunkObj = JSON.parse(block);

        if (!chunkObj || chunkObj.type !== "chapter_context_chunk") {
            __jreadMarkerLog("【JREAD无Web】章节分片格式错误：type不匹配");
            return __jreadMarkerSilent();
        }

        var sessionId = String(chunkObj.sessionId || "");
        var chunkIndex = parseInt(chunkObj.chunkIndex, 10);
        var chunkTotal = parseInt(chunkObj.chunkTotal, 10);
        var payload = String(chunkObj.payload || "");
        var encoding = String(chunkObj.encoding || "");

        if (!sessionId || isNaN(chunkIndex) || isNaN(chunkTotal) || chunkTotal <= 0 || !payload) {
            __jreadMarkerLog("【JREAD无Web】章节分片字段缺失");
            return __jreadMarkerSilent();
        }

        var safeSessionId = __jreadMarkerSafeFilePart(sessionId);
        var chunkFileName = "jread_ctx_chunks_" + safeSessionId + ".json";

        var store = __jreadMarkerReadJson(chunkFileName, null);
        if (!store || typeof store !== "object") {
            store = {
                sessionId: sessionId,
                chunkTotal: chunkTotal,
                encoding: encoding,
                chunks: {},
                updatedAt: Date.now()
            };
        }

        store.sessionId = sessionId;
        store.chunkTotal = chunkTotal;
        store.encoding = encoding || store.encoding || "";
        if (!store.chunks) store.chunks = {};
        store.chunks[String(chunkIndex)] = payload;
        store.updatedAt = Date.now();

        __jreadMarkerWriteText(chunkFileName, JSON.stringify(store, null, 2));

        var received = 0;
        for (var k in store.chunks) {
            if (store.chunks.hasOwnProperty(k) && store.chunks[k]) received++;
        }

        __jreadMarkerLog(
            "【JREAD无Web】收到章节分片 sessionId=" + sessionId +
            " chunk=" + (chunkIndex + 1) + "/" + chunkTotal +
            " received=" + received + "/" + chunkTotal
        );

        if (received < chunkTotal) {
            return __jreadMarkerSilent();
        }

        var combinedPayload = "";
        for (var i = 0; i < chunkTotal; i++) {
            var part = store.chunks[String(i)];
            if (!part) {
                __jreadMarkerLog("【JREAD无Web】分片未齐，缺少 chunkIndex=" + i);
                return __jreadMarkerSilent();
            }
            combinedPayload += String(part);
        }

        var decodedText = __jreadMarkerDecodePayload(combinedPayload, store.encoding || encoding);
        var chapterObj = JSON.parse(decodedText);

        if (!chapterObj || typeof chapterObj !== "object") {
            __jreadMarkerLog("【JREAD无Web】整章JSON解析失败：非对象");
            return __jreadMarkerSilent();
        }

        if (!chapterObj.sessionId) chapterObj.sessionId = sessionId;
        if (!chapterObj.type) chapterObj.type = "chapter_context";
        if (!chapterObj.updatedAt) chapterObj.updatedAt = Date.now();

        var chapterContent = String(chapterObj.chapterContent || "");
        var bookName = __jreadMarkerNormalizeBookName(
            chapterObj.bookName || chapterObj.book || chapterObj.bookTitle || chapterObj.title || ""
        );

        __jreadMarkerWriteText("jread_current_chapter.json", JSON.stringify(chapterObj, null, 2));

        if (bookName) {
            __jreadMarkerWriteText("cunfang.txt", bookName);
        }

        var meta = {
            source: "jread_tts_marker",
            sessionId: String(chapterObj.sessionId || sessionId),
            bookName: bookName,
            book: bookName,
            bookTitle: bookName,
            title: bookName,
            chapterTitle: String(chapterObj.chapterTitle || ""),
            chapterIndex: chapterObj.chapterIndex,
            contentHash: String(chapterObj.contentHash || ""),
            updatedAt: Date.now()
        };
        __jreadMarkerWriteText("cache_book_context_meta.json", JSON.stringify(meta, null, 2));

        __jreadMarkerLog(
            "【JREAD无Web】整章缓存写入成功 book=" + bookName +
            " chapter=" + String(chapterObj.chapterTitle || "") +
            " len=" + chapterContent.length +
            " chunks=" + chunkTotal
        );

        return __jreadMarkerSilent();
    } catch (e) {
        __jreadMarkerLog("【JREAD无Web】处理章节分片异常：" + e);
        return __jreadMarkerSilent();
    }
}

function __jreadMarkerHandlePointer(rawText) {
    var block = __jreadMarkerExtractBlock(
        rawText,
        "[[JREAD_PTR_V1]]",
        "[[/JREAD_PTR_V1]]"
    );

    if (!block) return null;

    try {
        var ptr = JSON.parse(block);
        if (!ptr || ptr.type !== "current_pointer") {
            __jreadMarkerLog("【JREAD无Web】当前位置marker格式错误：type不匹配");
            return __jreadMarkerSilent();
        }

        if (!ptr.updatedAt) ptr.updatedAt = Date.now();

        __jreadMarkerWriteText("jread_current_pointer.json", JSON.stringify(ptr, null, 2));

        var currentTextLen = String(ptr.currentText || "").length;
        __jreadMarkerLog(
            "【JREAD无Web】当前位置写入成功 sessionId=" + String(ptr.sessionId || "") +
            " start=" + String(ptr.startOffset) +
            " end=" + String(ptr.endOffset) +
            " textLen=" + currentTextLen
        );

        return __jreadMarkerSilent();
    } catch (e) {
        __jreadMarkerLog("【JREAD无Web】处理当前位置marker异常：" + e);
        return __jreadMarkerSilent();
    }
}

function handleJReadNoWebMarker(rawText) {
    rawText = String(rawText || "");

    if (rawText.indexOf("[[JREAD_CTX_CHUNK_V1]]") !== -1 &&
        rawText.indexOf("[[/JREAD_CTX_CHUNK_V1]]") !== -1) {
        return __jreadMarkerHandleCtxChunk(rawText);
    }

    if (rawText.indexOf("[[JREAD_PTR_V1]]") !== -1 &&
        rawText.indexOf("[[/JREAD_PTR_V1]]") !== -1) {
        return __jreadMarkerHandlePointer(rawText);
    }

    return null;
}
// ===================== JREAD_MARKER_PATCH_V1 结束 =====================
'''

HOOK = r'''
      // ===================== JREAD_MARKER_HANDLE_HOOK_V1：必须在 handleText 最开头 =====================
      try {
          var __jreadRawTextForMarker = String(text == null ? "" : text);
          var __jreadMarkerResult = handleJReadNoWebMarker(__jreadRawTextForMarker);
          if (__jreadMarkerResult && __jreadMarkerResult.handled) {
              return __jreadMarkerResult.result;
          }
      } catch (e_jread_marker_hook) {
          try { console.log("【JREAD无Web】marker入口异常，降级普通朗读：" + e_jread_marker_hook); } catch (e_jread_marker_log) {}
      }
      // ===================== JREAD_MARKER_HANDLE_HOOK_V1 结束 =====================
'''

def patch_json(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    if "code" not in data or not isinstance(data["code"], str):
        raise RuntimeError("这个 JSON 里没有 code 字符串，不像 J.TTS 朗读规则文件")

    code = data["code"]

    if "JREAD_MARKER_PATCH_V1" not in code:
        code = HELPER + "\n" + code

    if "JREAD_MARKER_HANDLE_HOOK_V1" not in code:
        pattern = re.compile(r'(handleText\s*:\s*function\s*\(\s*text\s*,\s*tagsData\s*\)\s*\{)')
        code, n = pattern.subn(r'\1\n' + HOOK, code, count=1)
        if n != 1:
            raise RuntimeError("没找到 handleText: function(text, tagsData) {，需要手动插入 HOOK")
    else:
        print("已存在 handleText marker hook，跳过重复插入")

    data["code"] = code
    data["name"] = "多角色朗读2.94【无Web直通整章Marker版】"
    data["version"] = max(int(data.get("version", 83) or 83), 94)

    out = path.with_name(path.stem + "_2.94_marker" + path.suffix)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=4), encoding="utf-8")
    return out

def main():
    if len(sys.argv) < 2:
        print("用法：python3 patch_jtts_marker.py 你的朗读规则.json")
        sys.exit(1)

    path = Path(sys.argv[1]).expanduser()
    if not path.exists():
        raise SystemExit("文件不存在：" + str(path))

    bak = path.with_suffix(path.suffix + ".bak")
    shutil.copy2(path, bak)
    print("已备份：", bak)

    out = patch_json(path)
    print("已生成：", out)
    print("下一步：把这个新 JSON 导入 J.TTS，并停用旧朗读规则。")

if __name__ == "__main__":
    main()
