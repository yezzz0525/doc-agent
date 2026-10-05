# 踩坑清单（PITFALLS）

> **这个文件是干什么的**：把本项目真实踩过的坑集中在一处，按「报错关键字」索引。
> 遇到报错时，**先用报错里的关键词搜本文档**（Ctrl+F），比翻代码快得多。
>
> 每条坑都标了：症状 → 根因 → 修法 → 为什么会踩。
> 最后一节是「通用排查方法」，比单个坑的解法更有长期价值。

---

## 怎么用这份文档

| 场景 | 怎么做 |
|---|---|
| 看到一条报错 | 拿报错里的英文关键词（如 `ClassCastException`）Ctrl+F 搜本文 |
| 想不起来报过什么 | 直接看下面的「按报错类型速查」表 |
| 想学排查思路 | 看最后一节「通用排查方法」——比单个坑的解法更值得记 |
| 想知道某个坑为什么存在 | 每条都有「为什么会踩」，面试时讲的就是这个 |

---

## 按报错类型速查

| 报错关键字 | 对应坑 | 严重程度 |
|---|---|---|
| `ClassCastException: DefaultToolCallingChatOptions cannot be cast to OpenAiChatOptions` | [坑 1](#坑-1强转classcastexception) | 阻塞 |
| `404: The model gpt-5-mini does not exist` | [坑 2](#坑-2模型名退回默认的-gpt-5-mini) | 阻塞 |
| 消息越滚越长 / token 暴涨 / AI 重复说话 | [坑 3](#坑-3消息重复膨胀) | 隐性（不报错但浪费） |
| `程序包 org.springframework.ai.xxx 不存在` | [坑 4](#坑-4spring-ai-20-模块化) | 阻塞 |
| 评测分数很低但代码没改过 | [坑 13](#坑-13评测集的标准答案错了) | **易误判** |
| `model not found` 但 Key 是好的 | [坑 5](#坑-5模型名写错或已下线) | 阻塞 |
| 明明配了却还报 401 / Key 无效 | [坑 6](#坑-6两个服务商共用一套配置) | 阻塞 |
| 检索结果全是废数据 / 换了模型就查不准 | [坑 7](#坑-7换embedding-模型必须重建索引) | 严重 |
| `Address already in use` / 端口被占用 | [坑 8](#坑-8端口被-workbuddy-环境变量抢占) | 小 |
| Docker 挂载失败 `D/: No such file or directory` | [坑 9](#坑-9git-bash-下-docker-挂载路径被改写) | 小 |
| pgvector 建表报主键/UUID 错误 | [坑 10](#坑-10pgvector-主键必须用-text-不能用-uuid) | 阻塞 |
| 测试里 `@MockBean` 报错找不到 | [坑 11](#坑-11spring-boot-4-移除了-mockbean) | 中 |
| 页脚显示的费用一直是错的 | [坑 12](#坑-12token-计价常量没跟着换模型改) | 小 |

---

## 三个「编译能过、只有跑起来才炸」的坑（最值得记）

这三个是同一个家族：**代码写得完全合理，编译通过、单元测试全绿，只有真正调用模型时才暴露。**
它们占本项目 bug 的一大半，面试时也最能体现「你踩过坑」。

### 坑 1：强转 ClassCastException

**症状**

```
ClassCastException: DefaultToolCallingChatOptions cannot be cast to OpenAiChatOptions
```

**根因**

```java
// ❌ 错：造出的是基类 DefaultToolCallingChatOptions
.chatOptions(ToolCallingChatOptions.builder().toolCallbacks(...).build())
```

`OpenAiChatModel.createRequest()` 内部做的是**硬强转**：

```java
(OpenAiChatOptions) prompt.getOptions()   // 基类实例当然转不过去
```

**修法**

```java
// ✅ 对：OpenAiChatOptions.Builder 继承自 DefaultToolCallingChatOptions.Builder
OpenAiChatOptions.builder()
        .model(chatModelName)          // 见坑 2，这个也要设
        .toolCallbacks(ToolCallbacks.from(tools))
        .toolContext(toolContext)
        .build()
```

**为什么会踩**：`ToolCallingChatOptions.builder()` 看起来是"更专门的类型"，用它是顺理成章的。
**关键事实**：继承方向是 `OpenAiChatOptions.Builder` **继承自** `DefaultToolCallingChatOptions.Builder`
（不是反过来），所以用子类那个两边需求一次满足。

---

### 坑 2：模型名退回默认的 gpt-5-mini

**症状**

```
404: The model `gpt-5-mini` does not exist or you do not have access to it.
```

**根因**

```java
// OpenAiChatOptions.java:60
public static final String DEFAULT_CHAT_MODEL = ChatModel.GPT_5_MINI.asString();
// OpenAiChatOptions.java:210（构造函数）
this.model = model != null ? model : DEFAULT_CHAT_MODEL;
```

手搓 `OpenAiChatOptions` 时**漏了 `.model()`**，框架就自动填了它为 OpenAI 硬编码的默认值 `gpt-5-mini`。
你配的是百炼 `qwen-flash`，发出去的却是 OpenAI 的模型名 → 百炼不认识。

**修法**

```java
// ✅ 模型名从配置注入，不在代码里硬写
public ReActAgentService(ChatModel chatModel,
        @Value("${spring.ai.openai.chat.model:qwen-flash}") String chatModelName, ...) {
    this.chatModelName = chatModelName;
}
// 然后集中在 optionsFor() 一个方法里设「模型名 + 工具 + 上下文」三样，避免再漏
```

**为什么会踩**：
- 走 `ChatClient` 的普通调用时框架会用自动配置的 options 补上模型名，**只有手搓 options 才暴露**
- 假 `ChatModel` 测试**根本不读模型名**，所以单测全绿也说明不了问题

> ⚠️ **同源的隐藏坑**：「撞上限强制收尾」那条路径原来写的是裸 `new Prompt(messages)`，
> 模型名和工具全丢，同样会退回 `gpt-5-mini`。而且**只在撞上轮数上限时才触发**，极难复现。
> 教训：**所有调模型的路径都要过同一个 `optionsFor()`。**

---

### 坑 3：消息重复膨胀

**症状**：不报错，但 AI 说的话里出现重复内容，token 成本暴涨。

**根因**（两个机制叠加，单看任何一个都正常）

1. `Prompt` 构造函数是 `this.messages = messages;` —— **零拷贝**，直接持有你那个 `ArrayList`。
   你之后往 list 里 add anything，这个 prompt 立刻跟着变。
2. `DefaultToolCallingManager.buildConversationHistoryAfterToolExecution()` 做的是
   `new ArrayList<>(previousMessages)` + `add(assistantMessage)` + `add(toolResponseMessage)`。

在调 `executeToolCalls` **之前**已经 `messages.add(assistant)` 了 →
`prompt.getInstructions()` 里已有一条 → history 返回 `[Sys, User, Asst, Asst, Tool]` →
用 `subList(messages.size(), history.size())` 取后半段 = `[Asst, Tool]` → **assistant 又被加一遍**。

**修法**

```java
// ✅ 传快照，切断共享引用
List<Message> snapshot = new ArrayList<>(messages);
Prompt prompt = Prompt.builder().messages(snapshot).chatOptions(...).build();

// ✅ 按类型提取，不要用 subList 算偏移量
List<Message> history = result.conversationHistory();
if (history != null) {
    for (Message m : history) {
        if (m instanceof ToolResponseMessage) messages.add(m);
    }
}
```

**为什么会踩**：两个 API 单独看都对，组合起来才出问题。**代码写出来完全合理，
只有断言消息条数才抓得到。** 上一次栽在同一条（`MessageWindowChatMemory`）。

> 📌 **通用教训**：凡是把 messages 列表交给框架对象，都要假设它**可能被持有或修改**。
> 要么传 `List.copyOf()`，要么事后按类型过滤，**永远不要靠 subList 算**。

---

## 配置类坑

### 坑 4：Spring AI 2.0 模块化

**症状**：`程序包 org.springframework.ai.vectorstore 不存在`（网上 1.x 教程明明能用）

**根因**：2.0 是模块化的，starter **只带"模型"的自动配置**，向量库/文档工具类要自己引坐标。

| 类 | 所属坐标 |
|---|---|
| `VectorStore` / `SimpleVectorStore` / `SearchRequest` | `org.springframework.ai:spring-ai-vector-store` |
| `Document` / `TokenTextSplitter` | `org.springframework.ai:spring-ai-commons` |
| `EmbeddingModel` | `spring-ai-model`（starter 已带） |
| `RetrievalAugmentationAdvisor` | `org.springframework.ai:spring-ai-rag` |

**为什么会踩**：包路径 `org.springframework.ai.vectorstore` **没变**，只是模块不再随 starter 附带。
所以报错时**不要改 import，要加依赖**——这是最反直觉的一点。

---

### 坑 5：模型名写错或已下线

**症状**：`model_not_found`，但 Key 是好的。

**排查顺序**（按这个顺序能省很多时间）

```bash
# 1. 先 curl 直接问服务商 —— 排除一切框架因素
curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"qwen-flash","messages":[{"role":"user","content":"hi"}],"max_tokens":5}'
```

- curl **成功** → 模型名没问题，是**框架那边发错了**（见坑 2）
- curl **失败** → 真的是模型名/Key/账户问题

**为什么会踩**：`qwen-flash` 这种名字看着像"参数"其实是"档位名"，
百炼下线某个档位时**不通知**，直接报 model_not_found。yaml 注释里已写备选。

---

### 坑 6：两个服务商共用一套配置

**症状**：明明 chat 的 Key 是好的，embedding 却报 401。

**根因与结论**：`spring.ai.openai.embedding.*` 会**覆盖**公共的 `spring.ai.openai.*`，
specific 优先。**混用两家 API（对话走百炼、向量走硅基流动）只需改配置，
不需要写自定义 EmbeddingModel Bean。**

**为什么会踩**：直觉会以为「一个 starter 一个配置」，实际上是两套独立配置项。

---

### 坑 7：换 embedding 模型必须重建索引

**症状**：换了 embedding 模型之后，检索结果全是乱的、答非所问。

**根因**：向量和模型**强绑定**。换了模型，向量空间完全不同，
但库里存的还是旧模型的向量 → 算出来的相似度没有意义。

**修法**：换模型必须重建索引。回退：本项目备份在
`data/vector-index.dashscope-v3.bak.json`（改回文件名 + 改回配置即可）。

**为什么会踩**：换模型是"配置改动"，看起来无害。但向量库里的数据是**旧模型的产物**，
这种"数据与代码/配置不同步"的 bug 最难发现——不报错，只是悄悄变笨。

---

## 环境类坑

### 坑 8：端口被 WorkBuddy 环境变量抢占

**症状**：启动报端口占用，但你明明没开别的服务。

**根因**：WorkBuddy 运行环境注入了 `SERVER__PORT=64166`，Spring Boot 宽松绑定会当成
`server.port` 并**覆盖 yaml 配置**。

**修法**：命令行启动须 `--server.port=8081` 或 unset 该变量。
**注意**：在 IDEA 里运行**不受影响**（IDEA 没这个环境变量）。

---

### 坑 9：Git Bash 下 Docker 挂载路径被改写

**症状**：`docker run -v 卷名:/d` 报 `du: D/: No such file or directory`

**根因**：Git Bash 会把 `/d` 自动转成 Windows 路径 `D:/`。

**修法**：先 `export MSYS_NO_PATHCONV=1`。

**为什么会踩**：只在 Git Bash 里出现，PowerShell / CMD 正常。**换终端就复现不了。**

---

### 坑 10：pgvector 主键必须用 TEXT

**症状**：建表/插入时报 UUID 相关错误。

**根因**：pgvector 配合 Spring AI 2.0 时，主键列要用 `TEXT` 类型，不能用 `UUID`。

**修法**：`application.yaml` 里 `doc-agent.vector-store` 的主键保持 TEXT。
详细说明见 `docs/01-pgvector-primary-key-pitfall.html`。

---

### 坑 11：Spring Boot 4 移除了 @MockBean

**症状**：测试类里 `@MockBean` 报找不到包。

**根因**：Spring Boot 4 移除了 `@MockBean`；`@MockitoBean` 也不在当前依赖里。

**修法**：**手写假实现**，例如 `ScriptedChatModel implements ChatModel`，
用 `AtomicInteger` 控制第几轮返回什么：

```java
public class ScriptedChatModel implements ChatModel {
    private final AtomicInteger round = new AtomicInteger(0);
    @Override public ChatResponse call(Prompt prompt) {
        receivedPrompts.add(prompt);          // 顺手记下来，可以断言
        if (round.getAndIncrement() == 0) return /*带工具调用的响应*/;
        return /*最终答案*/;
    }
}
```

**优点**：完全绕开 Spring 上下文 + Mockito，循环逻辑本就不依赖容器，反而更直白。

> ⚠️ **但要注意它的局限**：假 ChatModel **不读模型名、不读 URL**，
> 所以**它拦不住坑 1 和坑 2**。必须额外写「断言 options 的类型和字段」的测试。

---

### 坑 12：Token 计价常量没跟着换模型改

**症状**：页脚显示的累计费用一直是错的。

**根因**：`TokenUsageService` 里的价格常量是写死的，
换了服务商/模型后没同步改，费用显示就成了「按旧价算出来的假数字」。

**修法**：换模型时同步改 `CHAT_INPUT_PRICE_PER_MILLION` / `CHAT_OUTPUT_PRICE_PER_MILLION`。

**为什么会踩**：**不报错，只是数字不对**。这类 bug 不会主动暴露给你。

---

## 通用排查方法（比单个坑的解法更值得记）

### 方法 1：报错里有项目里从没出现过的名字 → 反向验证

本项目排查坑 2 用的方法：

1. `grep` 项目里**根本没有**这个模型名
2. 检查环境变量、IDEA 运行配置 → 也没有
3. `curl` 正常调用 → 一切正常
4. **故意传那个错的名字给服务商 → 报错文本和用户截图一字不差**

第 4 步是决定性的：**它同时证明了"是服务商返回的"和"是我们发错了"**，
把范围从"整个链路"缩小到"一个字段"。

### 方法 2：去 sources jar 看源码，别硬刚 javap

`D:/maven_repo/` 下**有 sources jar**，直接看方法体：

```bash
cd /tmp && "D:/JDK 25/bin/jar" xf /d/maven_repo/org/springframework/ai/spring-ai-model/2.0.0/spring-ai-model-2.0.0-sources.jar
grep -rn "DEFAULT_CHAT_MODEL" .              # 坑 2 就是这么找到的
grep -n "conversationHistory" org/springframework/ai/model/tool/DefaultToolCallingManager.java  # 坑 3
```

比 `javap -c` 反编译快得多，能直接看到方法体和注释。
只有 sources jar 缺失时才退用 `javap -p -c`。

### 方法 3：编译过、测试绿 ≠ 对

本项目 3 个最隐蔽的 bug（坑 1、2、3）**全都是编译通过、单元测试全绿**的。
因为它们错在"框架内部怎么理解我传的东西"，而假实现**根本不关心那些字段**。

**对策**：
- 手搓框架对象时，对**每个必设字段**都写一条断言测试
- 至少留一条**真实调用**的冒烟测试（可以打假 Key 只验装配，不花钱）

### 方法 4：修 bug 时顺手问一下"还有哪里会犯同样的错"

坑 2 修完发现「强制收尾」那条路径也漏了同样的字段——
**同一个错误模式在另一处重复出现，只是没被触发。**
修的时候要问：**这个模式在我代码里还有几处？**

---

## 评测相关

### 坑 13：评测集的标准答案错了

**症状**

刚写完评测集就跑，Recall 只有 **52%**，12 道题"失败"。
但代码一行没改过，检索逻辑也不可能这么差。

**根因**

造题时**凭印象猜了文件编号**——以为 profile 文档叫 `067-using-profiles`，
实际是 `064-features-profiles`。评测脚本忠实地报告"没命中"，因为它是对的，错的是标准答案。

**修法**

写个校验脚本，确保每个 `expectFiles` 里的文件在 `tools/spring-docs/` 里真实存在：

```python
names = {f[:-3] for f in os.listdir('tools/spring-docs') if f.endswith('.md')}
for q in questions:
    for f in q['expectFiles']:
        assert f in names, "标准答案指向不存在的文件: %s" % f
```

修正后 Recall = **96%**。

**为什么会踩（这才是关键）**

**标准答案错了，比没有标准答案更糟。**
没有评测集时你只是"感觉还行"；标准答案错了会让你**去优化一个本来没问题的检索**，
把代码越改越差——因为你有一个明确的、但是**错误**的优化目标。

>📌 **通用教训**：任何"尺子"本身都要先校准。
> 指标突然变差时，**先怀疑尺子，再怀疑被量的东西**。
> 排除顺序：① 标准答案对不对 → ② 环境变了吗 → ③ 代码有问题。

### 坑 14：同一份计算写了两遍，结果对不上

**症状**

Trace 里出现荒谬数据：**最后一轮的上下文比上一轮还小**（7 < 14）。上下文只会增长，不可能变小。

**根因**

同一个"统计上下文字符数"的逻辑写在了两个地方：
- 工具轮：累加了 `ToolResponseMessage` + `AssistantMessage`
- 收尾轮：只累加了 `AssistantMessage`，**漏了工具响应**

**修法**

抽成一个方法 `contextCharsOf(List<Message>)`，两处都调它。

**为什么会踩**

写第二遍的时候没意识到第一遍存在。**测试抓到的是"两遍算得不一样"，
而不是"哪一遍错了"**——所以断言必须写成「后一轮 ≥ 前一轮」这种**关系**，
而不是只断言某个绝对值。

> 📌 **通用教训**：同一份计算出现第二次，就抽成方法。
> 断言优先表达**不变量**（单调递增、集合相等），而不是具体数值。

---

## 危险操作红线

| ❌ 不要做 | ✅ 应该 |
|---|---|
| `taskkill /F /IM java.exe` | `taskkill /F /PID <具体PID>`（否则会杀掉用户 IDEA 里跑的服务） |
| 删 Docker 镜像前不查挂载 | 先 `docker inspect` 看有没有挂真实数据，空库才删 |
| 批量 sed 替换含 `<` `**` 的注释 | 用 Edit 工具（会被当正则元字符） |
| Maven 输出直接 grep | `> /tmp/x.log 2>&1` 后 `tr -d '\000' < /tmp/x.log`（编码问题） |
| curl 连 localhost 不加 `--noproxy` | 加 `--noproxy "*"`（会被代理拦截报 502） |
| `./mvnw.cmd` | 用缓存的 mvn 绝对路径（wrapper 会尝试联网下载） |

---

## 相关文件

- `README.md` —— 项目整体说明 + 架构图
- `docs/01-pgvector-primary-key-pitfall.html` —— pgvector 主键坑详解
- `docs/02-常见错误速查手册.html` —— 面向"报错长什么样"的速查
- `docs/演示脚本.md` —— 2 分钟演示话术
- 各个 `.java` 文件里的 `⚠️` 注释 —— 坑的**产生现场**，本文是**索引**
