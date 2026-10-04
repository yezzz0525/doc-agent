#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
doc-agent 配置自检脚本
---------------------------------------------------------------
换服务商 / 换模型之后，一键确认整套配置是否 OK。

检查 6 件事：
  1. 环境变量（缺失时可以直接粘贴 Key 输入，不会回显）
  2. 配置文件里的模型名、地址、向量库类型
  3. 阿里云百炼：Key 是否有效 + 模型名是否还存在
  4. 硅基流动：Key 是否有效 + 向量维度对不对
  5. 向量库：容器在不在、索引有多少片段
  6. 端到端冒烟测试：后端在跑的话，真的问它一句话看通不通

为什么需要它：报 401 的时候你看不出是哪错 —— Key 错、模型名不存在、
账号欠费、域名写错，在 IDEA 控制台里全都是同一行 UnauthorizedException。
这个脚本把这几种情况分开，并直接告诉你下一步该做什么。

怎么用：
    双击 tools\\check_config.bat            （会自动找 Python）
    python tools\\check_config.py            （命令行）
    powershell -ExecutionPolicy Bypass -File tools\\check-config.ps1   （无 Python）

脚本【不会】打印完整 Key，只显示前 8 位。
"""

import getpass
import io
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

# Windows 终端默认代码页是 GBK，脚本里有中文和全角符号，不强制 UTF-8 会乱码
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "src", "main", "resources", "application.yaml")

ok_n, warn_n, fail_n = 0, 0, 0
actions = []          # 收集"下一步该做什么"，最后统一打印


def ok(msg):
    global ok_n
    ok_n += 1
    print(f"  [ OK ] {msg}")


def warn(msg, action=None):
    global warn_n
    warn_n += 1
    print(f"  [注意] {msg}")
    if action:
        actions.append(action)


def fail(msg, action=None):
    global fail_n
    fail_n += 1
    print(f"  [失败] {msg}")
    if action:
        actions.append(action)


def section(t):
    print(f"\n=== {t} ===")


def mask(k):
    if not k:
        return "(未设置)"
    return k[:8] + "****" + k[-4:] if len(k) > 12 else k[:4] + "****"


def ask_key(prompt, env_name):
    """环境变量里没有就问用户要，输入不回显。

    ⚠️ 必须在没有 TTY（重定向、CI、非交互终端）时直接跳过 —— 否则
    getpass 会一直等输入把脚本卡死。我第一次写完自测就踩了这个。
    """
    try:
        if not sys.stdin.isatty():
            print("  （当前不是交互式终端，跳过输入）")
            return ""
        k = getpass.getpass(f"  请输入 {env_name}（输入不回显，粘贴后回车）: ").strip()
    except Exception as e:
        print(f"  （无法交互输入：{e}）")
        return ""
    if k:
        os.environ[env_name] = k          # 存起来供本次检查后续使用
        print(f"  已临时记录 {env_name}（仅本次运行有效）")
    return k


def post_json(url, api_key, payload, timeout=30):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8")), None
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", errors="replace")
        except Exception:
            detail = ""
        return None, f"HTTP {e.code} {detail}"
    except Exception as e:
        return None, f"请求失败：{e}"


def get_json(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8")), None
    except Exception as e:
        return None, str(e)


# ==================== 1. 环境变量 ====================
section("1. 环境变量")

dash_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
sf_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()

if dash_key:
    ok(f"DASHSCOPE_API_KEY = {mask(dash_key)}")
else:
    print("  DASHSCOPE_API_KEY 未设置")
    k = ask_key("DASHSCOPE_API_KEY（百炼对话）", "DASHSCOPE_API_KEY")
    if k:
        ok(f"DASHSCOPE_API_KEY = {mask(k)}")
        dash_key = k
    else:
        warn("跳过百炼相关检查",
             "去 https://bailian.console.aliyun.com/ 左侧「API-KEY」创建 Key，"
             "填到 IDEA 运行配置 → Environment variables")

if sf_key:
    ok(f"SILICONFLOW_API_KEY = {mask(sf_key)}")
else:
    print("  SILICONFLOW_API_KEY 未设置")
    k = ask_key("SILICONFLOW_API_KEY（硅基流动向量）", "SILICONFLOW_API_KEY")
    if k:
        ok(f"SILICONFLOW_API_KEY = {mask(k)}")
        sf_key = k
    else:
        warn("跳过硅基流动相关检查",
             "去 https://cloud.siliconflow.cn/account/ak 创建 Key（免费模型需实名）")

# ==================== 2. 配置文件 ====================
section("2. 配置文件")

chat_model = chat_base = embed_model = embed_base = vector_type = None
web_search = intent_cls = None

if not os.path.exists(CONFIG):
    fail(f"找不到 application.yaml（{CONFIG}）")
else:
    with io.open(CONFIG, encoding="utf-8") as f:
        lines = f.readlines()

    for line in lines:
        s = line.strip()
        for pat, name in ((r"^base-url:\s*(\S+)", "base"), (r"^model:\s*(\S+)", "model"),
                          (r"^type:\s*(\S+)", "type")):
            m = re.match(pat, s)
            if m:
                if name == "base" and not chat_base:
                    chat_base = m.group(1)
                elif name == "model" and not chat_model:
                    chat_model = m.group(1)
                elif name == "type" and not vector_type:
                    vector_type = m.group(1)

    in_embed = False
    for line in lines:
        raw = line.rstrip("\n")
        if re.match(r"^\s*embedding:\s*$", raw):
            in_embed = True
            continue
        if in_embed:
            m = re.match(r"^\s*base-url:\s*(\S+)", raw)
            if m:
                embed_base = m.group(1)
            m = re.match(r"^\s*model:\s*(\S+)", raw)
            if m:
                embed_model = m.group(1)
                break

    for line in lines:
        m = re.match(r"^\s*use-model-classify:\s*(\S+)", line)
        if m:
            intent_cls = m.group(1)
        m = re.match(r"^\s*enabled:\s*(\S+)", line)
        if m and web_search is None:
            web_search = m.group(1)
    ok("已读取 application.yaml")

print(f"    对话模型：{chat_model}")
print(f"    对话地址：{chat_base}")
print(f"    向量模型：{embed_model}")
print(f"    向量地址：{embed_base}")
print(f"    向量库  ：{vector_type}")
print(f"    联网搜索：{web_search}")
print(f"    模型分类：{intent_cls}")

# 域名体检：把最常见的错单独拎出来
if chat_base and "aliyun.com" in chat_base and "aliyuncs.com" not in chat_base:
    fail("对话地址的域名是 aliyun.com，正确应为 aliyuncs.com（少一个 c）",
         "把 application.yaml 里的 base-url 改成 https://dashscope.aliyuncs.com/compatible-mode/v1")
elif chat_base and "dashscope" in chat_base and "aliyuncs.com" not in chat_base:
    warn("对话地址看起来不是百炼标准域名，确认一下是否写错")
elif chat_base:
    ok("对话地址域名格式正确")

if embed_model and embed_model.startswith("Pro/"):
    warn(f"向量模型 {embed_model} 带 Pro/ 前缀 = 收费版，免费版是 BAAI/bge-m3",
         "去掉 Pro/ 前缀")

# ==================== 3. 百炼 ====================
section("3. 阿里云百炼（对话模型）")

if not dash_key:
    warn("跳过（没有 Key）")
elif not (chat_base and chat_model):
    warn("跳过（配置里没读到 base-url / model）")
else:
    resp, err = post_json(f"{chat_base}/chat/completions", dash_key,
                          {"model": chat_model, "messages": [{"role": "user", "content": "你好"}]})
    if resp:
        text = (resp.get("choices") or [{}])[0].get("message", {}).get("content", "")
        ok(f"Key 有效，模型 '{chat_model}' 可用")
        print(f"    模型回答：{text}")
        u = resp.get("usage") or {}
        if u:
            print(f"    本次用量：输入 {u.get('prompt_tokens')} / 输出 {u.get('completion_tokens')} token")
    else:
        low = (err or "").lower()
        if "html" in low or "doctype" in low:
            fail("返回的是 HTML 页面而不是 JSON —— 一定是地址写错了（不是 Key 问题）",
                 "核对 base-url：百炼应为 https://dashscope.aliyuncs.com/compatible-mode/v1")
        elif "arrearage" in low or "欠费" in low:
            fail("账号欠费！百炼欠费会让所有调用失败（这个坑本项目踩过）",
                 "去百炼控制台费用中心结清欠费，并开启「免费额度用完即停」")
        elif "401" in low or "unauthorized" in low or "invalidapikey" in low or "invalid api-key" in low:
            fail("Key 无效（401）",
                 "1) Key 是否复制完整  2) IDEA 里改完环境变量要重新 Run 才生效  "
                 "3) 若报的是「模型不存在」类的 401，换成控制台模型列表里的名字")
        elif "model" in low or "模型" in low or "not found" in low or "does not exist" in low:
            fail(f"模型名 '{chat_model}' 不存在",
                 "去 https://bailian.console.aliyun.com/ 模型列表页照抄，"
                 "备选：qwen3.8-flash / qwen3.6-flash / qwen3.7-plus")
        else:
            fail(f"调用失败：{err}")

# ==================== 4. 硅基流动 ====================
section("4. 硅基流动（向量模型）")

if not sf_key:
    warn("跳过（没有 Key）")
elif not (embed_base and embed_model):
    warn("跳过（配置里没读到向量模型的 base-url / model）")
else:
    resp, err = post_json(f"{embed_base}/embeddings", sf_key,
                          {"model": embed_model, "input": "测试", "encoding_format": "float"})
    if resp:
        dim = len((resp.get("data") or [{}])[0].get("embedding") or [])
        ok(f"Key 有效，向量模型 '{embed_model}' 可用（维度 {dim}）")
        if dim and dim != 1024:
            warn(f"维度是 {dim}，与配置的 1024 不一致",
                 "改 application.yaml 的 doc-agent.vector-store.dimensions，"
                 "并【重建索引】——换维度后旧向量全部作废")
    else:
        low = (err or "").lower()
        if "30014" in low or "token is invalid" in low:
            fail("Key 无效（Token is invalid）", "去 https://cloud.siliconflow.cn/account/ak 重新复制")
        elif "429" in low or "速率" in low or "限流" in low:
            warn("被限速了（429）—— 等十几秒再试，这不是配置问题")
        else:
            fail(f"调用失败：{err}")

# ==================== 5. 向量库 ====================
section("5. 向量库（PostgreSQL + pgvector）")


def run(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=20, shell=True,
                           encoding="utf-8", errors="replace")
        return (r.stdout or "").strip()
    except Exception:
        return ""


ps_out = run('docker ps --format "{{.Names}}|{{.Status}}"')
if not ps_out:
    warn("Docker 没运行或没有容器",
         "双击桌面「Docker Desktop」等右下角鲸鱼变绿 → 回到项目目录执行 docker compose up -d")
else:
    found = False
    for line in ps_out.splitlines():
        if "doc-agent-pg" in line:
            found = True
            if "healthy" in line.lower() or "up" in line.lower():
                ok(f"容器在跑：{line}")
            else:
                warn(f"容器状态异常：{line}", "docker compose logs --tail=50 doc-agent-pg 看原因")
    if not found:
        warn("没找到 doc-agent-pg 容器", "在项目根目录执行 docker compose up -d")

    cnt = run('docker exec doc-agent-pg psql -U docagent -d docagent -tAc "select count(*) from vector_store;"')
    if cnt and cnt.isdigit():
        if int(cnt) > 0:
            ok(f"向量库里有 {cnt} 个片段")
        else:
            warn("索引是空的", "启动应用后点页面上的「加载文档」建索引（这一步会调用 embedding 模型）")
    else:
        fail("连不上数据库，或 vector_store 表还不存在（表由应用启动时自动创建）")

# ==================== 6. 端到端冒烟测试 ====================
section("6. 端到端冒烟测试（后端在跑的话）")

st, serr = get_json("http://localhost:8081/api/status", timeout=5)
if st:
    ok(f"后端在跑，状态：{json.dumps(st, ensure_ascii=False)}")
    if st.get("chunkCount", 0) > 0:
        print("    正在问一句「你好」验证整条链路（含模型调用）…")
        try:
            req = urllib.request.Request(
                "http://localhost:8081/api/chat",
                data=json.dumps({"question": "你好"}).encode("utf-8"),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode("utf-8"))
            ans = (data.get("answer") or "").strip()
            if ans:
                ok("整条链路通了：前端 → 后端 → 意图路由 → 模型 → 返回")
                print(f"    模型回答：{ans[:80]}{'…' if len(ans) > 80 else ''}")
            else:
                warn("后端返回了空回答", "看 IDEA 控制台的最后几行日志")
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode("utf-8", errors="replace")[:200]
            except Exception:
                pass
            fail(f"提问接口返回 HTTP {e.code}：{detail}",
                 "401 → Key 无效；429 → 被限速等一会；500 → 看 IDEA 控制台堆栈")
        except Exception as e:
            warn(f"提问失败：{e}（可能是模型响应慢或被限速）")
else:
    warn(f"后端没在 8081 端口响应（{serr}）",
         "在 IDEA 里运行 DocAgentApplication 启动后端（启动日志里应出现「联网搜索已开启」）")

# ==================== 汇总 ====================
print("\n" + "=" * 50)
print(f"  通过 {ok_n}    注意 {warn_n}    失败 {fail_n}")
print("=" * 50)

if actions:
    print("\n需要你做的事：")
    for i, a in enumerate(actions, 1):
        print(f"  {i}. {a}")

print()
if fail_n == 0 and warn_n == 0:
    print("全部通过，可以开始用了。")
elif fail_n == 0:
    print("没有阻塞性问题，按上面「需要你做的事」处理即可。")
else:
    print("有阻塞性问题，先解决上面列的失败项。")
print("常见错误对照表：docs\\02-常见错误速查手册.html")
print()
