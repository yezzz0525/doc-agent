package com.example.docagent.service;

import com.example.docagent.dto.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写的 ReAct（Reasoning + Acting）循环
 *
 * <h3>ReAct 是什么</h3>
 * 一句话：<b>让模型「反复调工具，直到它觉得够了」</b>。
 * <pre>
 *   思考(Reasoning) → 行动(Acting) → 观察(Observation) → 思考 → ...
 * </pre>
 * 最小定义：<b>一个带工具的 LLM，加一个循环</b>。
 *
 * <h3>和之前那版的区别</h3>
 * 之前（{@code DocSearchTool} 挂给 ChatClient）：框架的 ToolCallingAdvisor 帮我们管循环，
 * 但它<b>藏在框架内部</b>，迭代次数没有硬上限，模型卡住会一直转到超时；
 * 而且中间过程看不到，只能等最终结果。
 * <pre>
 *   旧：用户问 → 框架循环（通常1 轮）→ 回答
 *   新：用户问 →【第1 轮思考】→ 调工具 →【观察】资料不够精确
 *             →【第 2 轮思考】→ 换个词再调 →【观察】这次够了 → 回答
 * </pre>
 *
 * <h3>为什么手写而不是用现成的</h3>
 * 手写换来三个框架给不了的东西：
 * <ol>
 *   <li><b>次数可控</b>：{@link #maxIterations} 是硬上限，超了强制收尾（框架版没有）</li>
 *   <li><b>过程可见</b>：每一轮做什么都通过 {@link ProgressListener} 报出去，
 *       前端能显示"正在检索…"，用户不会以为卡死</li>
 *   <li><b>能看懂</b>：停止条件、失败降级、超时保护全在眼前，面试时讲得清</li>
 * </ol>
 *
 * <h3>停止条件（三个，任意一个满足就结束）</h3>
 * <ol>
 *   <li><b>模型不再请求工具</b> —— 正常结束，最主要的情况</li>
 *   <li><b>达到最大轮数</b> —— 兜底，防止模型无限循环</li>
 *   <li><b>工具连续失败</b> —— 连续 {@link #maxConsecutiveFailures} 次抛异常就放弃，
 *       再查下去只是浪费 token</li>
 * </ol>
 *
 * <h3>为什么工具失败不直接抛</h3>
 * 一次问答可能调3~5 次工具，其中一次因为向量库抖动失败是常事。
 * 立刻抛异常会让整个回答作废，用户看到的是"系统错误"而不是答案。
 * 这里把失败信息<b>当成工具结果喂回模型</b>，让模型自己决定：
 * 换个词重试、还是直接用自己的知识回答。<b>把决策权交给模型</b>，这是 Agent 的思路。
 */
@Service
public class ReActAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentService.class);

    /** 最多迭代几轮。3~5 轮够用了，再多就是模型在原地打转 */
    private final int maxIterations;

    /** 工具连续失败多少次就放弃。防止向量库持续故障时空转烧 token */
    private final int maxConsecutiveFailures;

    private final ChatModel chatModel;

    /**
     * 要用的模型名，从配置读（{@code spring.ai.openai.chat.model}）
     *
     * <h3>⚠️ 这个字段绝对不能漏（踩过，运行期才炸）</h3>
     * 只要你<b>手搓</b> {@code OpenAiChatOptions}，就<b>必须显式 set 模型名</b>。
     * 因为它的构造函数是：
     * <pre>
     * this.model = model != null ? model : DEFAULT_CHAT_MODEL;
     * </pre>
     * 而 {@code DEFAULT_CHAT_MODEL = "gpt-5-mini"}（Spring AI 2.0 硬编码的 OpenAI 默认值）。
     * <p>
     * 所以漏set 的后果是：明明配的是百炼 qwen-flash，实际发出去的 model 却是
     * {@code gpt-5-mini} → 百炼不认识 → 404 model_not_found。
     * <p>
     * <b>为什么平时不踩</b>：走 {@code ChatClient} 的普通调用，框架会用
     * 自动配置里的默认 options 补上模型名；只有<b>手搓 options</b> 时才会暴露。
     */
    private final String chatModelName;

    private final ToolCallingManager toolCallingManager =
            DefaultToolCallingManager.builder().build();

    public ReActAgentService(ChatModel chatModel,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${spring.ai.openai.chat.model:qwen-flash}") String chatModelName,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${doc-agent.react.max-iterations:5}") int maxIterations,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${doc-agent.react.max-consecutive-failures:2}") int maxConsecutiveFailures) {
        this.chatModel = chatModel;
        this.chatModelName = chatModelName;
        this.maxIterations = Math.max(1, maxIterations);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        log.info("ReAct 循环已就绪：模型 {}，最多 {} 轮，工具连续失败 {} 次即放弃",
                this.chatModelName, this.maxIterations, this.maxConsecutiveFailures);
    }

    /** 循环过程中每一步的回调，让上层能把进度推给前端 */
    public interface ProgressListener {
        /**
         * 报告一次工具调用
         *
         * @param iteration  第几轮（从 1 开始）
         * @param toolName   调的工具名
         * @param summary    给前端看的一句话描述，如"检索「事务失效」"
         */
        void onToolCall(int iteration, String toolName, String summary);
    }

    /** 什么都不做的监听器，方便只关心结果时使用 */
    private static final ProgressListener NO_OP = (iteration, toolName, summary) -> { };

    /**
     * 跑一次完整的 ReAct 循环
     *
     * @param systemPrompt 系统提示词（角色设定 + 防幻觉约束）
     * @param userQuestion 用户问题
     * @param tools        挂给模型的工具
     * @param toolContext  工具需要的上下文（比如 conversationId，用于取回引用来源）
     * @param listener     进度回调，可以为 null
     * @return 循环结果：最终答案 + 实际用了几个工具 + 是否被轮数上限截断
     */
    public ReactResult run(String systemPrompt,
                           String userQuestion,
                           Object[] tools,
                           Map<String, Object> toolContext,
                           ProgressListener listener) {

        ProgressListener progress = listener == null ? NO_OP : listener;
        long t0 = System.nanoTime();

        List<Message> messages = new ArrayList<>();
        messages.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
        messages.add(new org.springframework.ai.chat.messages.UserMessage(userQuestion));

        int toolCallCount = 0;
        int consecutiveFailures = 0;
        boolean truncated = false;

        // ===== Trace：记录每一轮发生了什么 =====
        // 用途：事后能回答"这次为什么慢 / 为什么答错"，
        // 而不是只能看到一个最终答案干瞪眼。这也是评测集能定位问题的前提。
        List<TraceStep> trace = new ArrayList<>();
        long promptCharsBefore = systemPrompt.length() + userQuestion.length();

        for (int i = 1; i <= maxIterations; i++) {
            // ===== 第 1 步：思考（调模型，让它决定要不要调工具）=====
            // ⚠️ 第二个坑：Prompt 构造函数是 `this.messages = messages;`——零拷贝，
            //   它直接持有我们这个 ArrayList。后面我们往 messages 里 add(assistant)，
            //   这个 prompt 看到的 instructions 也会跟着变。所以这里必须传**快照**。
            //   不传快照的话，executeToolCalls 返回的 conversationHistory 会是
            //   [System, User, Assistant, Assistant, ToolResponse]（assistant 出现两次）。
            List<Message> snapshot = new ArrayList<>(messages);
            Prompt prompt = Prompt.builder()
                    .messages(snapshot)
                    .chatOptions(optionsFor(toolContext, tools))
                    .build();

            ChatResponse response = chatModel.call(prompt);
            AssistantMessage assistant = (AssistantMessage) response.getResult().getOutput();
            long callMs = (System.nanoTime() - t0) / 1_000_000;

            // ===== 停止条件 1：模型不再请求工具 =====
            // 这是正常结束——它觉得手上的资料够回答了
            if (!assistant.hasToolCalls()) {
                String answer = assistant.getText();
                long cost = (System.nanoTime() - t0) / 1_000_000;
                trace.add(new TraceStep(i, "answer", "-", callMs, contextCharsOf(messages), 0));
                log.info("ReAct 循环结束：第 {} 轮模型决定直接回答，共调工具 {} 次，耗时 {} ms，"
                                + "上下文累计约 {} 字（初始 {} 字）",
                        i, toolCallCount, cost, contextCharsOf(messages), promptCharsBefore);
                return new ReactResult(answer, toolCallCount, false, i, cost, trace);
            }

            // ===== 第 2 步：行动（执行模型要调的工具）=====
            messages.add(assistant);
            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            List<String> names = new ArrayList<>(calls.size());
            for (AssistantMessage.ToolCall call : calls) {
                names.add(call.name());
            }
            progress.onToolCall(i, String.join("+", names), describe(calls));

            try {
                // 这行在 lambda 里用，所以先存成final 局部变量
                final long callMsFinal = callMs;
                // 这一步内部会：找到对应的 @Tool 方法 → 执行 → 把结果包成 ToolResponseMessage
                ToolExecutionResult result =
                        toolCallingManager.executeToolCalls(prompt, response);

                // 工具返回值追加进消息列表 —— 这就是"观察"环节。
                // 模型下一轮就能看到自己刚查到的东西。
                //
                // ⚠️⚠️ 这里踩了两个坑，才最终写出下面这 5 行：
                //
                // 坑一：conversationHistory() 不是「只有工具响应」，
                //   而是「传入 prompt 的全部消息 + assistant + 工具响应」。
                //   直接 addAll 会让 SystemMessage/UserMessage/上几轮的 AssistantMessage
                //   全部重复追加，消息列表指数级膨胀，token 成本暴涨。
                //
                // 坑二：也不能用 history.subList(messages.size(), history.size()) 这种
                //   偏移量算术。因为 Prompt 是零拷贝持有 messages 的（构造函数
                //   `this.messages = messages;`），我们在调 executeToolCalls 之前
                //   已经 messages.add(assistant) 了，于是 prompt.getInstructions()
                //   里也有 assistant → history 里 assistant 出现两次 →
                //   subList 算出来是 [Assistant, ToolResponse] → assistant 又被加一遍。
                //   这个 bug 表现得很隐蔽：代码看起来完全合理，只有断言消息条数才抓得到。
                //
                // 正确做法：只取类型为 ToolResponseMessage 的消息。
                // 这是本项目第二次栽在「Spring AI 的 List 是共享引用而不是副本」上，
                // 上一次是 MessageWindowChatMemory。所以这里的经验是：
                //     凡是把 messages 列表交给框架对象，都要假设它可能被持有或修改，
                //     要么传 List.copyOf()，要么事后按类型过滤，不要靠下标算。
                List<Message> history = result.conversationHistory();
                if (history != null) {
                    for (Message m : history) {
                        if (m instanceof ToolResponseMessage) {
                            messages.add(m);
                        }
                    }
                }

                // 工具成功返回（哪怕内容是"没查到"），失败计数清零
                consecutiveFailures = 0;
                toolCallCount += calls.size();

                // 记trace：这一轮查了什么词、拿回多少字的内容。
                // 检索回来的字数就是下一轮的 token 成本，也是"上下文在膨胀"的证据。
                // ⚠️ 用普通循环而不是 stream().forEach()：
                //    在 lambda 里累加外层的 long 会编译失败（ Effectively Final 被破坏），
                //    顺带 stream 对这种"要累加"的场景本来就不如for 直观。
                int retrievedChars = 0;
                if (history != null) {
                    for (Message m : history) {
                        if (m instanceof ToolResponseMessage trm) {
                            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                                retrievedChars += r.responseData().length();
                            }
                        }
                    }
                }
                int contextNow = contextCharsOf(messages);
                trace.add(new TraceStep(i, String.join("+", names),
                        extractQuery(calls.get(0).arguments()), callMsFinal, contextNow, retrievedChars));
                // 每轮都打一行，看起来像这样：
                // [TRACE] q=starter it=2 tools=searchSpringDocs callMs=2100 ctx=3410 fetched=1580
                log.info("[TRACE] q={} it={} tools={} callMs={} ctx={} fetched={}",
                        abbreviate(userQuestion), i, names, callMsFinal, contextNow, retrievedChars);

            } catch (Exception e) {
                // ===== 停止条件 3：工具连续失败太多次 =====
                consecutiveFailures++;
                log.warn("ReAct 第 {} 轮工具执行失败（连续第 {} 次）：{}",
                        i, consecutiveFailures, e.getMessage());
                trace.add(new TraceStep(i, "TOOL_ERROR", e.getMessage(), callMs, 0, 0));

                if (consecutiveFailures >= maxConsecutiveFailures) {
                    // 放弃循环，把已有资料交给模型做最后一次总结
                    log.warn("ReAct 工具连续失败 {} 次，放弃循环并降级为单轮回答", maxConsecutiveFailures);
                    return new ReactResult(forceAnswer(messages), toolCallCount, true, i,
                            (System.nanoTime() - t0) / 1_000_000, trace);
                }

                // 把失败当成"工具结果"喂回模型，让它自己决定换个词还是直接答。
                // 这一步是本类的核心设计：不让单次失败毁掉整个回答。
                messages.add(failureMessage(calls, e));
            }
        }

        // ===== 停止条件 2：达到最大轮数 =====
        // 兜底。再让模型总结一次现有资料，别让用户等来一个空回答
        truncated = true;
        log.warn("ReAct 达到最大轮数 {}，强制收尾", maxIterations);
        String finalAnswer = forceAnswer(messages);
        return new ReactResult(finalAnswer, toolCallCount, truncated, maxIterations,
                (System.nanoTime() - t0) / 1_000_000, trace);
    }

    /** 日志里缩短问题文本，否则每行都刷一长串 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "-";
        }
        return s.length() <= 20 ? s : s.substring(0, 20) + "…";
    }

    /**
     * 算出上下文里跟"喂给模型"相关的字符数 —— token 成本的直接指标
     *
     * <p><b>只算工具响应和助手自己的话</b>，不算 System/User 提示。
     * 因为前者才是<b>每轮都在累加</b>的部分，是成本失控的源头；
     * 后者每轮都一样，算进去只会把数字冲淡。
     *
     * <p>⚠️ 这个方法当初写错过一次：收尾分支只累加了 AssistantMessage 的文本，
     * 漏掉 ToolResponseMessage，导致 trace 里出现「最后一轮上下文比上一轮还小」
     * 的荒谬数据。**同一份计算只能有一个实现**，两处各写一遍必然对不上。
     */
    private static int contextCharsOf(List<Message> messages) {
        int sum = 0;
        for (Message m : messages) {
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    sum += r.responseData().length();
                }
            } else if (m instanceof AssistantMessage am && am.getText() != null) {
                sum += am.getText().length();
            }
        }
        return sum;
    }

    /**
     * 兜底收尾：让模型基于已有资料给个总结
     *
     * <p>注意这里<b>必须</b>走 {@link #optionsFor(String)} 造 options，不能用裸
     * {@code new Prompt(messages)}——那样模型名会退回框架默认的 gpt-5-mini，
     * 兜底路径也就跟着404 了（而且只在撞上轮数上限时才触发，更难复现）。
     */
    private String forceAnswer(List<Message> messages) {
        return chatModel.call(Prompt.builder()
                        .messages(new ArrayList<>(messages))
                        .chatOptions(optionsFor(null, null))
                        .build())
                .getResult().getOutput().getText();
    }

    /**
     * 造这次调用要用的 options —— <b>模型名、工具、上下文三样都在这儿设</b>
     *
     * <h3>为什么抽出来</h3>
     * 手搓 options 一共踩过三个坑，其中两个是「漏设某个字段」：
     * <ol>
     *   <li>用错builder 类型 → ClassCastException</li>
     *   <li>漏 {@code .model()} → 退回默认的 gpt-5-mini → 百炼 404</li>
     * </ol>
     * 散在代码里很容易再漏一次，<b>集中到这一个方法，漏了一眼就能看出来</b>。
     *
     * @param toolContext 传给工具的上下文，传 null 表示这轮不挂工具（收尾总结用）
     * @param tools       工具数组，可以为 null
     */
    private org.springframework.ai.chat.prompt.ChatOptions optionsFor(Map<String, Object> toolContext,
                                                                     Object[] tools) {
        var builder = OpenAiChatOptions.builder()
                // ⚠️⚠️ 最容易漏的一个：OpenAiChatOptions 构造函数是
                //   this.model = model != null ? model : DEFAULT_CHAT_MODEL;
                // 而 DEFAULT_CHAT_MODEL = "gpt-5-mini"（框架为 OpenAI 硬编码的默认值）。
                // 漏了它 → 明明配了百炼 qwen-flash，实际发出去的 model 却是 gpt-5-mini
                //   → 404 model_not_found。
                //   走 ChatClient 的普通调用不会踩（框架用自动配置的 options 补了），
                //   只有手搓 options 才暴露。
                .model(chatModelName);

        // 收尾总结那轮不挂工具，避免模型又要工具、又拿不到结果
        if (tools != null && tools.length > 0) {
            builder.toolCallbacks(ToolCallbacks.from(tools))
                   .toolContext(toolContext == null ? Map.of() : toolContext);
        }
        return builder.build();
    }

    /**
     * 工具执行抛异常时，构造一条"失败消息"喂回模型
     *
     * 关键设计：告诉模型"这个工具挂了"，而不是把异常堆栈扔给它。
     * 模型看到这句话会自己决定——换个查询词重试，或者用自己的知识先答着。
     */
    private Message failureMessage(List<AssistantMessage.ToolCall> calls, Exception e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (AssistantMessage.ToolCall call : calls) {
            // ToolResponse 是 record，构造参数顺序是 (id, name, responseData)
            responses.add(new ToolResponseMessage.ToolResponse(
                    call.id(),
                    call.name(),
                    "【工具暂时不可用】" + reason
                            + "。请不要重复调用同一个工具：可以换个查询词再试一次，"
                            + "或者直接基于你已有的知识回答，并提示用户稍后重试。"));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /** 把工具调用翻译成一句给用户看的话 */
    private String describe(List<AssistantMessage.ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "调用工具";
        }
        AssistantMessage.ToolCall first = calls.get(0);
        String query = extractQuery(first.arguments());
        String label = "searchSpringDocs".equals(first.name()) ? "检索知识库" : "调用 " + first.name();
        if (query != null && !query.isBlank()) {
            return label + "「" + query + "」";
        }
        return label;
    }

    /**
     * 从工具参数 JSON 里抠出查询词，让进度提示更具体
     *
     * <p>不引JSON 库：参数形如 {@code {"query":"事务失效"}}，
     * 用正则取第一个字符串值就够了。这不是生产级做法（会被转义字符坑到），
     * 但对"给用户看一句话"这个需求完全够用，也省一个依赖。
     */
    private String extractQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(argumentsJson);
        return matcher.find() ? matcher.group(2) : null;
    }

    /**
     * 一次工具调用的 trace 记录
     *
     * <p><b>为什么要留这些字段</b>：出了问题光看最终答案是没用的，
     * 得知道「第几轮查了什么词、那一轮慢在哪、上下文涨到多大」。
     * 这些数字也是优化成本的依据——上下文是每轮累加的，
     * 知道它涨多快，才知道该不该限制轮数。
     *
     * @param iteration第几轮
     * @param tools     调了哪些工具（失败时是 TOOL_ERROR）
     * @param query     检索词（从工具参数里抽出来的）
     * @param callMs    这一轮调模型到拿到响应花了多久
     * @param contextChars  这一轮结束时上下文累计字符数（token 成本的直接指标）
     * @param fetchedChars  这一轮工具拿回多少字符
     */
    public record TraceStep(int iteration, String tools, String query,
                            long callMs, int contextChars, int fetchedChars) {}

    /**
     * ReAct 循环的结果
     *
     * @param answer最终答案（模型的最后一段输出）
     * @param toolCallCount 累计调了几次工具（用于日志和可观测）
     * @param truncated    是否因为撞上轮数上限/工具连续失败而被迫收尾
     * @param iterations   实际跑了几轮
     * @param totalMs      整个循环耗时
     * @param trace        每轮的详细轨迹，用于事后定位问题
     */
    public record ReactResult(String answer, int toolCallCount, boolean truncated, int iterations,
                              long totalMs, List<TraceStep> trace) {

        /** 归档成字符串，方便日志里一眼看清这次跑得顺不顺 */
        @Override
        public String toString() {
            return String.format("iterations=%d tools=%d truncated=%s answerLen=%d totalMs=%d",
                    iterations, toolCallCount, truncated,
                    answer == null ? 0 : answer.length(), totalMs);
        }
    }

    /**
     * 记录循环过程中被引用到的资料，供上层取走去渲染引用卡片
     *
     * <p>ReAct 可能查好几轮，每轮都可能命中新片段。
     * 这里按「文件+序号」去重合并，保证前端卡片里不会出现重复条目。
     */
    public static List<SourceRef> mergeSources(List<SourceRef> accumulated, List<SourceRef> added) {
        if (added == null || added.isEmpty()) {
            return accumulated == null ? List.of() : accumulated;
        }
        if (accumulated == null || accumulated.isEmpty()) {
            return List.copyOf(added);
        }
        Map<String, SourceRef> merged = new LinkedHashMap<>();
        for (SourceRef s : accumulated) {
            merged.put(s.file() + "#" + s.snippet(), s);
        }
        for (SourceRef s : added) {
            // 同一个文件同一段只保留一次，但保留分数更高的那个
            String key = s.file() + "#" + s.snippet();
            SourceRef old = merged.get(key);
            if (old == null || (s.score() != null && s.score() > old.score())) {
                merged.put(key, s);
            }
        }
        return List.copyOf(merged.values());
    }
}
