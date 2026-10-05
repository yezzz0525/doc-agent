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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
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

    private final ToolCallingManager toolCallingManager =
            DefaultToolCallingManager.builder().build();

    public ReActAgentService(ChatModel chatModel,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${doc-agent.react.max-iterations:5}") int maxIterations,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${doc-agent.react.max-consecutive-failures:2}") int maxConsecutiveFailures) {
        this.chatModel = chatModel;
        this.maxIterations = Math.max(1, maxIterations);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        log.info("ReAct 循环已就绪：最多 {} 轮，工具连续失败 {} 次即放弃",
                this.maxIterations, this.maxConsecutiveFailures);
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

        for (int i = 1; i <= maxIterations; i++) {
            // ===== 第 1 步：思考（调模型，让它决定要不要调工具）=====
            Prompt prompt = Prompt.builder()
                    .messages(messages)
                    .chatOptions(ToolCallingChatOptions.builder()
                            // ToolCallbacks.from(...) 把带 @Tool 注解的对象转成框架能识别的回调
                            .toolCallbacks(ToolCallbacks.from(tools))
                            // toolContext 让工具方法能拿到 conversationId
                            .toolContext(toolContext == null ? Map.of() : toolContext)
                            .build())
                    .build();

            ChatResponse response = chatModel.call(prompt);
            AssistantMessage assistant = (AssistantMessage) response.getResult().getOutput();

            // ===== 停止条件 1：模型不再请求工具 =====
            // 这是正常结束——它觉得手上的资料够回答了
            if (!assistant.hasToolCalls()) {
                String answer = assistant.getText();
                long cost = (System.nanoTime() - t0) / 1_000_000;
                log.info("ReAct 循环结束：第 {} 轮模型决定直接回答，共调工具 {} 次，耗时 {} ms",
                        i, toolCallCount, cost);
                return new ReactResult(answer, toolCallCount, false, i);
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
                // 这一步内部会：找到对应的 @Tool 方法 → 执行 → 把结果包成 ToolResponseMessage
                ToolExecutionResult result =
                        toolCallingManager.executeToolCalls(prompt, response);

                // 工具返回值追加进消息列表 —— 这就是"观察"环节。
                // 模型下一轮就能看到自己刚查到的东西。
                messages.addAll(result.conversationHistory());

                // 工具成功返回（哪怕内容是"没查到"），失败计数清零
                consecutiveFailures = 0;
                toolCallCount += calls.size();

            } catch (Exception e) {
                // ===== 停止条件 3：工具连续失败太多次 =====
                consecutiveFailures++;
                log.warn("ReAct 第 {} 轮工具执行失败（连续第 {} 次）：{}",
                        i, consecutiveFailures, e.getMessage());

                if (consecutiveFailures >= maxConsecutiveFailures) {
                    // 放弃循环，把已有资料交给模型做最后一次总结
                    log.warn("ReAct 工具连续失败 {} 次，放弃循环并降级为单轮回答", maxConsecutiveFailures);
                    String partial = chatModel.call(new Prompt(
                            messages.toArray(new Message[0]))).getResult().getOutput().getText();
                    return new ReactResult(partial, toolCallCount, true, i);
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
        String finalAnswer = chatModel.call(new Prompt(messages.toArray(new Message[0])))
                .getResult().getOutput().getText();
        return new ReactResult(finalAnswer, toolCallCount, truncated, maxIterations);
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
     * ReAct 循环的结果
     *
     * @param answer最终答案（模型的最后一段输出）
     * @param toolCallCount 累计调了几次工具（用于日志和可观测）
     * @param truncated    是否因为撞上轮数上限/工具连续失败而被迫收尾
     * @param iterations   实际跑了几轮
     */
    public record ReactResult(String answer, int toolCallCount, boolean truncated, int iterations) {

        /** 归档成字符串，方便日志里一眼看清这次跑得顺不顺 */
        @Override
        public String toString() {
            return String.format("iterations=%d tools=%d truncated=%s answerLen=%d",
                    iterations, toolCallCount, truncated, answer == null ? 0 : answer.length());
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
