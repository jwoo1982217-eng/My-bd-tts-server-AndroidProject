#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import re
import shutil
import sys
from pathlib import Path

# 复用第一版脚本里的 HELPER / HOOK
from patch_jtts_marker import HELPER, HOOK

def looks_like_speech_rule(obj):
    if not isinstance(obj, dict):
        return False
    code = obj.get("code")
    if not isinstance(code, str):
        return False

    name = str(obj.get("name", ""))
    has_handle = re.search(r'''['"]?handleText['"]?\s*:\s*function\s*\(\s*text''', code) is not None
    has_name_hint = ("多角色朗读" in name) or ("speechRule" in name) or ("朗读" in name)

    return has_handle or (has_name_hint and "handleText" in code)

def walk_candidates(obj, path="root", out=None):
    if out is None:
        out = []

    if looks_like_speech_rule(obj):
        out.append((path, obj))

    if isinstance(obj, dict):
        for k, v in obj.items():
            walk_candidates(v, path + "." + str(k), out)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            walk_candidates(v, path + "[" + str(i) + "]", out)

    return out

def patch_code(code):
    if "JREAD_MARKER_PATCH_V1" not in code:
        code = HELPER + "\n" + code

    if "JREAD_MARKER_HANDLE_HOOK_V1" not in code:
        pattern = re.compile(r'''((?:['"])?handleText(?:['"])?\s*:\s*function\s*\([^)]*\)\s*\{)''')
        code, n = pattern.subn(lambda m: m.group(1) + "\n" + HOOK, code, count=1)
        if n != 1:
            raise RuntimeError("找到规则 code，但没能定位 handleText 函数开头")
    else:
        print("已存在 marker hook，跳过重复插入")

    return code

def patch_file(path):
    raw = path.read_text(encoding="utf-8")
    data = json.loads(raw)

    candidates = walk_candidates(data)

    if not candidates:
        raise RuntimeError(
            "没有在这个 JSON 里找到带 code + handleText 的朗读规则。"
            "可能你选到的不是主朗读规则文件。"
        )

    print("找到候选朗读规则：")
    for i, (p, obj) in enumerate(candidates):
        print("  [%d] %s | name=%s" % (i, p, obj.get("name", "")))

    # 默认只改第一个匹配到的主朗读规则
    target_path, target = candidates[0]
    print("准备修改：", target_path, "|", target.get("name", ""))

    target["code"] = patch_code(target["code"])
    target["name"] = "多角色朗读2.94【无Web直通整章Marker版】"

    try:
        target["version"] = max(int(target.get("version", 83) or 83), 94)
    except Exception:
        target["version"] = 94

    out = path.with_name(path.stem + "_2.94_marker" + path.suffix)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=4), encoding="utf-8")
    return out

def main():
    if len(sys.argv) < 2:
        print("用法：python3 patch_jtts_marker_v2.py 规则文件.json")
        sys.exit(1)

    path = Path(sys.argv[1]).expanduser()
    if not path.exists():
        raise SystemExit("文件不存在：" + str(path))

    bak = path.with_suffix(path.suffix + ".bak2")
    shutil.copy2(path, bak)
    print("已备份：", bak)

    out = patch_file(path)
    print("已生成：", out)
    print("下一步：把这个 _2.94_marker.json 导入 J.TTS，并停用旧规则。")

if __name__ == "__main__":
    main()
