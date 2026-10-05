#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检索评测脚本 —— 项目的"尺子"

======================================================================
它量什么
======================================================================
只量**检索层**：给一个中文问题，向量库能不能把正确的英文文档捞出来。

  Recall@K = 命中的题数 / 总题数
  （K = 每次取几段，默认 4，与 DocSearchTool.TOP_K 一致）

**刻意不量生成层**。理由：
  - 检索准不准和答得好不好是两个独立问题，混在一起就说不清是哪坏了
  - 生成层要调大模型，慢（十几秒/题）且烧钱
  - 检索层是零成本、零延迟的，能随手跑，才有用

用法
======================================================================
  # 先启动后端（IDEA 里跑，或命令行）
  python tools/eval/run_eval.py                 # 用默认 4
  python tools/eval/run_eval.py --topk 2        # 对比召回率随 K 变化
  python tools/eval/run_eval.py --category hard # 只跑难题
  python tools/eval/run_eval.py --show-failed   # 打印失败题的检索结果明细
  python tools/eval/run_eval.py --base http://127.0.0.1:8082

⚠️ 注意：脚本用的是 --noproxy 等价逻辑。
   Windows 上 curl 常被代理拦截报 502，requests 也要注意这个问题。
"""

import argparse
import json
import os
import sys
import time
from collections import defaultdict
from urllib.request import Request, urlopen
from urllib.parse import urlencode

HERE = os.path.dirname(os.path.abspath(__file__))
QUESTIONS_FILE = os.path.join(HERE, "questions.json")
DEFAULT_BASE = "http://127.0.0.1:8081"


# ----------------------------------------------------------------------
# 抓一个关键坑：绕过系统代理
# ----------------------------------------------------------------------
# urllib 默认会读环境变量 HTTP_PROXY / http_proxy。如果本机开了代理
# （Clash 之类），请求 127.0.0.1 也会被塞进代理，拿到 502 或连接失败。
# ProxyHandler({}) = 显式"不使用任何代理"。
import urllib.request
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def retrieve(base, query, topk, timeout=30):
    """调后端的纯检索接口，返回 (结果列表, 耗时毫秒)"""
    url = "%s/api/eval/retrieve?%s" % (base.rstrip("/"), urlencode(
        {"query": query, "topK": topk}))
    t0 = time.time()
    req = Request(url, headers={"Accept": "application/json"})
    with _OPENER.open(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("hits", []), int((time.time() - t0) * 1000)


def file_key(path):
    """
    从 source 里取出文件主名（去掉扩展名）。

    片段的 source 形如 "tools/spring-docs/004-features-ssl.md"，
    而 questions.json 里写的是 "004-features-ssl"，
    所以两边都要规范化成同一个 key 才能比。
    """
    name = os.path.basename(str(path))
    if name.endswith(".md"):
        name = name[:-3]
    return name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE, help="后端地址")
    ap.add_argument("--topk", type=int, default=4, help="每次取几段")
    ap.add_argument("--category", default=None, help="只跑某一类：config/howto/concept/compare")
    ap.add_argument("--difficulty", default=None, help="只跑某个难度：easy/medium/hard")
    ap.add_argument("--show-failed", action="store_true", help="打印失败题明细")
    args = ap.parse_args()

    with open(QUESTIONS_FILE, encoding="utf-8") as f:
        config = json.load(f)
    questions = config["questions"]

    if args.category:
        questions = [q for q in questions if q["category"] == args.category]
    if args.difficulty:
        questions = [q for q in questions if q["difficulty"] == args.difficulty]

    if not questions:
        print("没有匹配的题目，检查 --category / --difficulty")
        return 1

    print("=" * 68)
    print("doc-agent 检索评测   Recall@%d" % args.topk)
    print("后端：%s" % args.base)
    print("题数：%d" % len(questions))
    print("=" * 68)
    print()

    passed = 0
    failed = []
    latencies = []
    # 统一存 [命中数, 总数]，比维护两个计数器不容易出错
    by_category = defaultdict(lambda: [0, 0])
    by_difficulty = defaultdict(lambda: [0, 0])

    for i, q in enumerate(questions, 1):
        expect = set(q["expectFiles"])
        by_category[q["category"]][1] += 1
        by_difficulty[q["difficulty"]][1] += 1

        try:
            hits, ms = retrieve(args.base, q["question"], args.topk)
            latencies.append(ms)
        except Exception as e:
            print("  [%2d/%2d] %s  ✗ 请求失败：%s" % (i, len(questions), q["id"], e))
            failed.append((q, None, "请求失败：%s" % e))
            continue

        got_files = [file_key(h.get("file", "")) for h in hits]
        ok = any(g in expect for g in got_files)

        if ok:
            passed += 1
            mark = "✓"
            by_category[q["category"]][0] += 1
            by_difficulty[q["difficulty"]][0] += 1
        else:
            failed.append((q, got_files, None))
            mark = "✗"

        top1 = got_files[0] if got_files else "-"
        print("  [%2d/%2d] %s %s  %-28s → %-30s %4dms"
              % (i, len(questions), q["id"], mark, q["question"][:26], top1[:28], ms))

    total = len(questions)
    print()
    print("=" * 68)
    print("总分  Recall@%d = %d/%d = %.1f%%"
          % (args.topk, passed, total, 100.0 * passed / total))
    if latencies:
        latencies.sort()
        avg = sum(latencies) / len(latencies)
        print("延迟  平均 %dms · 中位 %dms · 最慢 %dms"
              % (avg, latencies[len(latencies) // 2], latencies[-1]))
    print("=" * 68)

    print()
    print("【按分类】")
    for cat, (ok, tot) in sorted(by_category.items()):
        if tot:
            print("  %-10s %d/%d = %5.1f%%" % (cat, ok, tot, 100.0 * ok / tot))
    print("【按难度】")
    for d, (ok, tot) in sorted(by_difficulty.items()):
        if tot:
            print("  %-10s %d/%d = %5.1f%%" % (d, ok, tot, 100.0 * ok / tot))

    if failed:
        print()
        print("【失败的题】共 %d 道 —— 这些就是要优化的方向" % len(failed))
        for q, got, err in failed:
            print("  %s [%s] %s" % (q["id"], q["difficulty"], q["question"]))
            print("       期望来自：%s" % ", ".join(q["expectFiles"]))
            if got is not None:
                print("       实际捞到：%s" % (", ".join(got) if got else "（空）"))
            if err:
                print("       原因：%s" % err)

    if args.show_failed and failed:
        print()
        print("=" * 68)
        print("失败题检索结果明细")
        print("=" * 68)
        for q, got, err in failed:
            if err:
                continue
            try:
                hits, _ = retrieve(args.base, q["question"], args.topk)
            except Exception:
                continue
            print()
            print("%s  %s" % (q["id"], q["question"]))
            print("  期望关键词：%s" % ", ".join(q["keywords"]))
            for h in hits:
                mark = "★" if file_key(h.get("file", "")) in set(q["expectFiles"]) else " "
                snip = (h.get("snippet") or "").replace("\n", " ")[:100]
                print("  %s %-30s score=%-6s %s"
                      % (mark, file_key(h.get("file", ""))[:28], h.get("score"), snip))

    return 0 if passed == total else 0  # 有失败不算脚本出错，返回码统一给 0


if __name__ == "__main__":
    sys.exit(main())
