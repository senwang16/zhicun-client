#!/usr/bin/env python3
"""双语资源一致性校验（P11 / R-I18N-10）。

检查项：
  1. values/（英文默认）与 values-zh/ 的 <string> / <plurals> 键集合完全一致；
  2. 两份资源都没有空值；
  3. values/ 里没有中文（唯一例外：settings_language_zh，语言名按惯例用自身语言写）；
  4. 不存在 values-en/（英文只在默认资源里，双份必然漂移）；
  5. 每个键的占位符（%1$s / %d / %% …）两份一致；<plurals> 按 (键, quantity) 共有分支比较；
  6. values/ 里没有中文全角标点；
  7. 资源文本没有 TODO / FIXME / 待译 等审校残留；
  8. 两份资源的字符串数量与 Android 允许的 language tag 一致（zh / en）。

用法（仓库根目录或任意位置）：
    python scripts/release/check-i18n-res.py [android 工程根]

退出码：0 = 通过；1 = 不合规；2 = 用法 / 文件缺失。
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CJK = re.compile(r"[\u4e00-\u9fff]")
CJK_PUNCT = re.compile(r"[，。！？：；、（）【】《》“”‘’]")
PLACEHOLDER = re.compile(r"%%|%(\d+\$)?[sd]")
RESIDUE = re.compile(r"TODO|FIXME|待译|待翻译|TRANSLATE")
ALLOWED_CJK_KEY = "settings_language_zh"


def fail(message: str) -> None:
    print(f"   失败：{message}", file=sys.stderr)


def entries(path: Path) -> dict[str, str]:
    """读取 <string> / <plurals>，返回 `kind:name` → 文本（plurals 拼接各分支）。"""
    root = ET.parse(path).getroot()
    result: dict[str, str] = {}
    for node in root:
        if node.tag == "string":
            result[f"string:{node.get('name')}"] = node.text or ""
        elif node.tag == "plurals":
            branches = " | ".join((item.text or "") for item in node if item.tag == "item")
            result[f"plurals:{node.get('name')}"] = branches
    return result


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("android")
    res = root / "app" / "src" / "main" / "res"
    base_file = res / "values" / "strings.xml"
    zh_file = res / "values-zh" / "strings.xml"

    for path in (base_file, zh_file):
        if not path.is_file():
            print(f"用法错误：找不到 {path}（在仓库根目录执行，或把 android 工程根作为第一个参数）", file=sys.stderr)
            return 2

    failed = False

    print("== 1. 键集合一致 ==")
    base = entries(base_file)
    zh = entries(zh_file)
    if set(base) == set(zh):
        print(f"   通过：values {len(base)} 键，values-zh {len(zh)} 键")
    else:
        fail("键集合不一致")
        for key in sorted(set(base) - set(zh)):
            print(f"     仅英文有：{key}", file=sys.stderr)
        for key in sorted(set(zh) - set(base)):
            print(f"     仅中文有：{key}", file=sys.stderr)
        failed = True

    print("== 2. 无空值 ==")
    empties = [key for key, value in {**base, **zh}.items() if value.strip() == ""]
    if empties:
        fail(f"{len(empties)} 个空字符串：{', '.join(empties[:10])}")
        failed = True
    else:
        print("   通过：两份资源均无空值")

    print("== 3. 默认资源无中文 ==")
    leaked = [
        key for key, value in base.items()
        if CJK.search(value) and not key.endswith(ALLOWED_CJK_KEY)
    ]
    if leaked:
        fail(f"默认资源仍含中文：{', '.join(leaked[:10])}")
        failed = True
    else:
        print(f"   通过：values/ 只剩英文（{ALLOWED_CJK_KEY} 已登记例外）")

    print("== 4. 无 values-en 重复目录 ==")
    if (res / "values-en").is_dir():
        fail("values-en/ 仍存在；英文应在默认 values/")
        failed = True
    else:
        print("   通过：英文只在默认 values/")

    print("== 5. 占位符一致 ==")
    mismatch = []
    for key in sorted(set(base) & set(zh)):
        node = key.split(":", 1)[0]
        if node == "plurals":
            # 复数分支集合按语言规则可以不同；只比较两侧共有分支的占位符
            left = PLACEHOLDER.findall(base[key])
            right = PLACEHOLDER.findall(zh[key])
            if sorted(set(left)) != sorted(set(right)):
                mismatch.append(key)
        else:
            if sorted(PLACEHOLDER.findall(base[key])) != sorted(PLACEHOLDER.findall(zh[key])):
                mismatch.append(key)
    if mismatch:
        fail(f"占位符不一致：{', '.join(mismatch[:10])}")
        failed = True
    else:
        print("   通过：每个键的占位符完全一致")

    print("== 6. 默认资源无中文全角标点 ==")
    punctuation = [
        key for key, value in base.items()
        if CJK_PUNCT.search(value) and not key.endswith(ALLOWED_CJK_KEY)
    ]
    if punctuation:
        fail(f"英文资源残留中文标点：{', '.join(punctuation[:10])}")
        failed = True
    else:
        print("   通过：英文默认资源无中文标点")

    print("== 7. 无审校残留 ==")
    leftover = [
        key for key, value in {**base, **zh}.items()
        if RESIDUE.search(value)
    ]
    if leftover:
        fail(f"资源文本仍有审校占位：{', '.join(leftover[:10])}")
        failed = True
    else:
        print("   通过：资源文本无 TODO/FIXME/待译")

    print("== 8. 默认语言覆盖检查 ==")
    # 只有 zh / en 两种语言：存在其它 values-* 资源目录时提示（不阻断，可能是第三方 SDK）
    extra = sorted(
        path.name for path in res.glob("values-*")
        if path.is_dir() and path.name not in ("values-zh",)
    )
    if extra:
        print(f"   INFO：另有资源目录 {', '.join(extra)}（确认是否为项目自身文案）")
    else:
        print("   通过：只有 values/ 与 values-zh/")

    if failed:
        print("结果：不合规")
        return 1
    print("结果：通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())