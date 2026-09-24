# -*- coding: utf-8 -*-
"""Build compact in-app pinyin dict from open-source Rime Ice (雾凇拼音).

Sources (CC0 / open community dicts under iDvel/rime-ice):
  - cn_dicts/8105.dict.yaml  常用字
  - cn_dicts/base.dict.yaml   基础词库

Output:
  assets/ime/pinyin_dict.txt.gz   gzip 文本词库
  assets/ime/ATTRIBUTION.txt      来源说明
"""
from __future__ import annotations

import gzip
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = Path(__file__).resolve().parent / "vendor"
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "ime"

# 每拼音键最多保留候选数（按权重降序）
MAX_PER_KEY = 24
# 多字词最低权重（雾凇词频，过滤超低频；兼顾 APK/内存）
MIN_WORD_WEIGHT = 50
# 单字最低权重
MIN_CHAR_WEIGHT = 1
# 多字词最长字数
MAX_WORD_LEN = 6

# 简历/人社业务域高频短语（覆盖开源库优先级）
DOMAIN_BOOST = {
    "jianli": [("简历", 9_999_999)],
    "xingming": [("姓名", 9_999_999)],
    "dianhua": [("电话", 9_999_999)],
    "youxiang": [("邮箱", 9_999_999)],
    "gongzuo": [("工作", 9_999_999)],
    "jingli": [("经历", 9_999_998), ("经理", 9_000_000)],
    "jingyan": [("经验", 9_999_999)],
    "xuexiao": [("学校", 9_999_999)],
    "zhuanye": [("专业", 9_999_999)],
    "xueli": [("学历", 9_999_999)],
    "gangwei": [("岗位", 9_999_999)],
    "gongsi": [("公司", 9_999_999)],
    "zhiwei": [("职位", 9_999_999)],
    "xiangmu": [("项目", 9_999_999)],
    "jineng": [("技能", 9_999_999)],
    "biyeyuanxiao": [("毕业院校", 9_999_999)],
    "gongzuojingyan": [("工作经验", 9_999_999)],
    "xiangmujingyan": [("项目经验", 9_999_999)],
    "gerenjianjie": [("个人简介", 9_999_999)],
    "lianxifangshi": [("联系方式", 9_999_999)],
    "shenfenzheng": [("身份证", 9_999_999)],
    "shebao": [("社保", 9_999_999)],
    "shebaochaxun": [("社保查询", 9_999_999)],
    "yanglao": [("养老", 9_999_999)],
    "yanglaojin": [("养老金", 9_999_999)],
    "jiuye": [("就业", 9_999_999)],
    "jiuyedengji": [("就业登记", 9_999_999)],
    "peixun": [("培训", 9_999_999)],
    "rencai": [("人才", 9_999_999)],
    "rengcai": [("人才", 9_999_999)],
    "zhongcai": [("仲裁", 9_999_999)],
    "laodong": [("劳动", 9_999_999)],
    "laodonghetong": [("劳动合同", 9_999_999)],
    "hetong": [("合同", 9_999_999)],
    "nihao": [("你好", 9_999_999)],
    "ninhao": [("您好", 9_999_999)],
    "qingwen": [("请问", 9_999_999)],
    "xiexie": [("谢谢", 9_999_999)],
    "zenme": [("怎么", 9_999_999)],
    "shenme": [("什么", 9_999_999)],
}


def parse_rime_dict(path: Path, min_weight: int, max_len: int) -> dict[str, dict[str, int]]:
    """Return py -> {word: weight}."""
    out: dict[str, dict[str, int]] = defaultdict(dict)
    started = False
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not started:
                if line.strip() == "...":
                    started = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            py = parts[1].strip().lower().replace(" ", "").replace("'", "")
            if not word or not py or not re.fullmatch(r"[a-z]+", py):
                continue
            if len(word) > max_len:
                continue
            weight = 1
            if len(parts) >= 3:
                try:
                    weight = int(float(parts[2]))
                except ValueError:
                    weight = 1
            if weight < min_weight:
                continue
            prev = out[py].get(word, 0)
            if weight > prev:
                out[py][word] = weight
    return out


def merge(dst: dict[str, dict[str, int]], src: dict[str, dict[str, int]]) -> None:
    for py, words in src.items():
        bucket = dst.setdefault(py, {})
        for w, score in words.items():
            if score > bucket.get(w, 0):
                bucket[w] = score


def main() -> None:
    char_path = VENDOR / "8105.dict.yaml"
    base_path = VENDOR / "base.dict.yaml"
    if not char_path.exists() or not base_path.exists():
        raise SystemExit(f"missing vendor dicts under {VENDOR}")

    print("parsing 8105...")
    table = parse_rime_dict(char_path, MIN_CHAR_WEIGHT, 1)
    print(f"  keys={len(table)}")
    print("parsing base...")
    words = parse_rime_dict(base_path, MIN_WORD_WEIGHT, MAX_WORD_LEN)
    print(f"  keys={len(words)}")
    merge(table, words)

    for py, pairs in DOMAIN_BOOST.items():
        bucket = table.setdefault(py, {})
        for w, score in pairs:
            bucket[w] = max(bucket.get(w, 0), score)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    plain = OUT_DIR / "pinyin_dict.txt"
    gz_path = OUT_DIR / "pinyin_dict.txt.gz"
    attr = OUT_DIR / "ATTRIBUTION.txt"

    lines: list[str] = [
        "# Built from iDvel/rime-ice (雾凇拼音) 8105 + base",
        "# Format: pinyin=word:weight,word:weight",
        "# License note: see ATTRIBUTION.txt",
    ]
    total_words = 0
    for py in sorted(table.keys()):
        items = sorted(table[py].items(), key=lambda x: (-x[1], x[0]))[:MAX_PER_KEY]
        if not items:
            continue
        body = ",".join(f"{w}:{s}" for w, s in items)
        lines.append(f"{py}={body}")
        total_words += len(items)

    text = "\n".join(lines) + "\n"
    # 仅打包 gzip，避免 APK 同时带明文
    if plain.exists():
        plain.unlink()
    with gzip.open(gz_path, "wt", encoding="utf-8", compresslevel=9) as gzf:
        gzf.write(text)

    attr.write_text(
        "\n".join(
            [
                "拼音词库来源 / Dictionary attribution",
                "",
                "1) 雾凇拼音 rime-ice（坚持使用）",
                "   https://github.com/iDvel/rime-ice",
                "   Files used: cn_dicts/8105.dict.yaml, cn_dicts/base.dict.yaml",
                "   Upstream aggregates open corpora (THUOCL, community dicts, etc.).",
                "",
                "2) Domain boost phrases for HR/resume robot app (project local).",
                "",
                "Vendor cache: scripts/vendor/ (gitignored recommended)",
                "Rebuild: python scripts/build_open_pinyin_dict.py",
                "",
            ]
        ),
        encoding="utf-8",
    )

    print(f"keys={len(table)} entries≈{total_words}")
    print(f"gzip ={gz_path.stat().st_size} bytes")
    print("done ->", gz_path)


if __name__ == "__main__":
    main()
