# tools

## 目录

| 路径 | 用途 |
|---|---|
| `spring-docs/` | 爬下来的 Spring 官方文档（87 篇 md，英文原文） |
| `check_config.py` | 配完 API Key 后先跑一遍自检，避免"看着像 Key 问题其实是地址错" |
| `eval/` | **检索评测集** —— 项目的"尺子"，详见下 |

---

## eval/ 检索评测

### 为什么需要它

**没有评测 = 只能说「感觉还行」。** 有了它才能说「Recall 从 62% 提到 96%」。

评测只测**检索层**（给的资料对不对），不测生成层（答得好不好），
因为前者零成本零延迟、后者十几秒一次还烧钱。

### 用法

```bash
# 先启动后端（IDEA 里跑，或命令行 java -jar）

python tools/eval/run_eval.py                    # 25 道题，约 4 秒
python tools/eval/run_eval.py --topk 2           # 对比 K 对召回率的影响
python tools/eval/run_eval.py --difficulty hard  # 只跑难题
python tools/eval/run_eval.py --category config  # 只跑某一类
python tools/eval/run_eval.py --show-failed      # 打印失败题的检索明细
```

### 当前基线（改任何配置前先记住）

| 指标 | 数值 |
|---|---|
| Recall@4 | **96.0%**（24/25） |
| 平均延迟 | 150 ms |
| 单次检索拉回字数 | 约 17920 字 ← **token 成本大头** |

### 文件说明

| 文件 | 说明 |
|---|---|
| `questions.json` | 25 道题 + 标准答案。**`expectFiles` 里的编号必须与 `spring-docs/` 真实文件名一致** |
| `run_eval.py` | 评测脚本，只调 `GET /api/eval/retrieve`（不调大模型） |

### ⚠️ 一个重要的坑

**第一版评测跑出来只有 52%，但其中 12 道是我自己的标准答案写错了**——
造题时凭印象猜了文件编号（以为 `067-using-profiles`，实际 `064-features-profiles`）。

**标准答案错了比没有标准答案更糟**：它会给出方向完全相反的结论，
让人去"优化"一个本来没问题的检索。

所以：改 `questions.json` 后，务必让每个 `expectFiles` 里的文件在
`spring-docs/` 里**真实存在**。当前版本已全部校验过。

---

## 详细报告

完整分析（含 topK 对比、失败题原因、优化方向）见 [`../docs/评测报告.md`](../docs/评测报告.md)。
