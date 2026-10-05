package com.example.docagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReAct 循环的端到端验证
 *
 * <h3>为什么用「手写假模型」而不是 {@code @MockBean}</h3>
 * 上一版 ReAct 就是「编译通过、一跑就ClassCastException」——
 * 那种错只有把 options 真正交给 OpenAiChatModel 才会暴露。
 * 所以这个测试刻意<b>让真实的 ReActAgentService 跑完整循环</b>，
 * 只把最外层的 ChatModel 换成可控的假实现。
 *
 * <p>不用 Spring 上下文 + Mockito，是因为 Spring Boot 4 里
 * {@code @MockBean} 已移除、{@code @MockitoBean} 又不在当前依赖里，
 * 少踩一个坑是一个坑。循环逻辑本身不依赖容器，手写假模型反而更直白。
 *
 * <h3>这个测试真正拦住的 bug</h3>
 * 之前用的是 {@code ToolCallingChatOptions.builder()}，造出基类
 * {@code DefaultToolCallingChatOptions}，而 {@code OpenAiChatModel.createRequest()}
 * 内部做的是 {@code (OpenAiChatOptions) prompt.getOptions()} 强转 → 运行时 ClassCastException。
 * <b>那行代码编译得过、只有真跑才炸</b>，就是这个测试存在的意义。
 */
class ReActAgentServiceTest {

    /** 假的对话模型：按调用次数返回不同结果，模拟「先要工具、后给答案」 */
    private static class ScriptedChatModel implements ChatModel {

        private final AtomicInteger round = new AtomicInteger(0);
        private final List<Prompt> receivedPrompts = new java.util.ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedPrompts.add(prompt);
            if (round.getAndIncrement() == 0) {
                // 第 1 轮：模型说"我先查一下文档"，并给出工具调用
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-1", "function", "searchSpringDocs", "{\"query\":\"事务失效\"}");
                AssistantMessage withToolCall = AssistantMessage.builder()
                        .content("我先查一下文档")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(withToolCall)));
            }
            // 第 2 轮：模型不再要工具，给出最终答案
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("事务失效的原因是代理对象未被 Spring 管理。"))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }

    /** 占位工具：验证工具能被挂上、能被执行到，不依赖真实向量库 */
    static class NoopTool {
        @Tool(description = "占位工具，不做任何事")
        public String searchSpringDocs(@ToolParam(description = "任意关键词") String query) {
            return "【模拟检索结果】" + query;
        }
    }

    @Test
    void 两轮后应该收敛出最终答案() {
        ScriptedChatModel fakeModel = new ScriptedChatModel();
        ReActAgentService agent = new ReActAgentService(fakeModel, "qwen-flash", 5, 2);

        ReActAgentService.ReactResult result = agent.run(
                "你是 Spring 助手",
                "@Transactional 为什么会失效？",
                new Object[]{new NoopTool()},
                Map.of("conversationId", "test-1"),
                null);

        // 第 2 轮模型不再要工具 → 循环正常结束，答案取自第 2 轮
        assertThat(result.answer()).contains("代理对象");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
        assertThat(result.toolCallCount()).isEqualTo(1);
    }

    /**
     * 回归测试：options 必须是 OpenAiChatOptions 而不是基类
     *
     * <p>这个断言是为了守住那个 ClassCastException。真实运行时 OpenAiChatModel 会强转，
     * 这里提前检查类型，把"跑起来才发现"变成"测试就发现"。
     */
    @Test
    void 传给模型的options必须是OpenAiChatOptions() {
        ScriptedChatModel fakeModel = new ScriptedChatModel();
        ReActAgentService agent = new ReActAgentService(fakeModel, "qwen-flash", 5, 2);

        agent.run("你是 Spring 助手", "问题", new Object[]{new NoopTool()}, Map.of(), null);

        // 至少收到过一次请求
        assertThat(fakeModel.receivedPrompts).isNotEmpty();
        for (Prompt p : fakeModel.receivedPrompts) {
            assertThat(p.getOptions())
                    .as("OpenAiChatModel.createRequest() 内部会把它强转成 OpenAiChatOptions，"
                            + "类型不对就是运行期 ClassCastException")
                    .isInstanceOf(org.springframework.ai.openai.OpenAiChatOptions.class);
        }
    }

    /**
     * 工具结果应该被喂回给模型，且消息列表<b>不能重复膨胀</b>
     *
     * <p>这个测试抓过一个真 bug：{@code conversationHistory()} 返回的是
     * 「传入的 prompt 全部消息 + 工具响应」，直接 addAll 会让 SystemMessage /
     * UserMessage 反复追加，消息列表指数级膨胀。所以断言两件事：
     * 工具返回内容在第二轮上下文里，且系统提示只出现一次。
     */
    @Test
    void 工具结果应该被喂回给模型且消息不重复() {
        ScriptedChatModel fakeModel = new ScriptedChatModel();
        ReActAgentService agent = new ReActAgentService(fakeModel, "qwen-flash", 5, 2);

        agent.run("你是 Spring 助手", "事务失效", new Object[]{new NoopTool()}, Map.of(), null);

        assertThat(fakeModel.receivedPrompts).hasSizeGreaterThanOrEqualTo(2);
        Prompt second = fakeModel.receivedPrompts.get(1);

        // 工具返回的文本在 ToolResponseMessage.getResponses() 里，不在 getText() 里
        String allText = second.getInstructions().stream()
                .map(ReActAgentServiceTest::textOf)
                .reduce("", String::concat);

        // 工具结果确实进了上下文
        assertThat(allText).contains("模拟检索结果");

        // 关键断言：系统提示只该出现一次，消息列表没被重复追加
        String systemPrompt = "你是 Spring 助手";
        int occurrences = allText.split(java.util.regex.Pattern.quote(systemPrompt), -1).length - 1;
        assertThat(occurrences)
                .as("conversationHistory() 含整个 prompt，直接 addAll 会让消息重复膨胀")
                .isEqualTo(1);

        // 消息数应该是：System + User + Assistant(带工具调用) + ToolResponse = 4
        assertThat(second.getInstructions())
                .as("第 2 轮的上下文不该出现重复消息")
                .hasSize(4);
    }

    /** 各种 Message 类型的文本提取（ToolResponseMessage 的内容在 responses 里） */
    private static String textOf(Message message) {
        StringBuilder sb = new StringBuilder();
        if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
            trm.getResponses().forEach(r -> sb.append(r.responseData()));
            return sb.toString();
        }
        if (message instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
            return am.getText() == null ? "" : am.getText();
        }
        if (message instanceof org.springframework.ai.chat.messages.SystemMessage sm) {
            return sm.getText() == null ? "" : sm.getText();
        }
        if (message instanceof org.springframework.ai.chat.messages.UserMessage um) {
            return um.getText() == null ? "" : um.getText();
        }
        return "";
    }

    /**
     * 传给模型的 options 必须带上模型名（守住gpt-5-mini 那个404）
     *
     * <p>这个测试对应一个真实事故：手搓 {@code OpenAiChatOptions} 时漏了
     * {@code .model()}，而它的构造函数是
     * {@code this.model = model != null ? model : DEFAULT_CHAT_MODEL}，
     * {@code DEFAULT_CHAT_MODEL} 恰恰是 OpenAI 的 {@code gpt-5-mini}——
     * 于是明明配的百炼 qwen-flash，实际发出去的 model 却是 gpt-5-mini，百炼直接 404。
     *
     * <p><b>为什么这个断言单靠运行期才发现不了</b>：假 ChatModel 根本不读 model 名，
     * 所以单测全绿、真调模型才炸。有这个断言后，漏了立刻在这里挂。
     */
    @Test
    void options必须带模型名不能退回默认的gpt5mini() {
        ScriptedChatModel fakeModel = new ScriptedChatModel();
        ReActAgentService agent = new ReActAgentService(fakeModel, "qwen-flash", 5, 2);

        agent.run("你是 Spring 助手", "问题", new Object[]{new NoopTool()}, Map.of(), null);

        assertThat(fakeModel.receivedPrompts).isNotEmpty();
        for (Prompt p : fakeModel.receivedPrompts) {
            String model = ((org.springframework.ai.openai.OpenAiChatOptions) p.getOptions()).getModel();
            assertThat(model)
                    .as("漏设model 会退回框架默认的 gpt-5-mini，百炼不认这个模型名 → 404")
                    .isEqualTo("qwen-flash");
        }
    }

    /**
     * 撞上轮数上限时走的是 forceAnswer 这条兜底路径，
     * 它<b>也必须</b>带模型名（用裸 new Prompt(messages) 会退回默认值）
     */
    @Test
    void 强制收尾时也不能丢模型名() {
        ChatModel stubborn = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String model = prompt.getOptions() == null ? null
                        : ((org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions()).getModel();
                if (!"qwen-flash".equals(model)) {
                    throw new IllegalStateException("模型名丢了：" + model);
                }
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-x", "function", "searchSpringDocs", "{\"query\":\"反复查\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("继续查")
                        .toolCalls(List.of(toolCall))
                        .build())));
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
            }
        };

        ReActAgentService agent = new ReActAgentService(stubborn, "qwen-flash", 3, 2);
        ReActAgentService.ReactResult result =
                agent.run("系统提示", "问题", new Object[]{new NoopTool()}, Map.of(), null);

        // 每一次调用（含最后收尾那次）都检查过了，没抛就说明模型名都在
        assertThat(result.truncated()).isTrue();
    }

    /** 模型一直要工具时，应该撞上轮数上限并强制收尾，而不是无限循环 */
    @Test
    void 撞上轮数上限应该强制收尾() {
        // 一个只会要工具、永远不给答案的模型
        ChatModel stubborn = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                        "call-x", "function", "searchSpringDocs", "{\"query\":\"反复查\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("继续查")
                        .toolCalls(List.of(toolCall))
                        .build())));
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
            }
        };

        ReActAgentService agent = new ReActAgentService(stubborn, "qwen-flash", 3, 2);
        ReActAgentService.ReactResult result = agent.run(
                "系统提示", "问题", new Object[]{new NoopTool()}, Map.of(), null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.iterations()).isEqualTo(3);
    }

    /** 进度回调应该每轮都被调用，且带上轮次和描述 */
    @Test
    void 进度回调应该被调用() {
        ScriptedChatModel fakeModel = new ScriptedChatModel();
        ReActAgentService agent = new ReActAgentService(fakeModel, "qwen-flash", 5, 2);

        var steps = new java.util.ArrayList<String>();
        agent.run("系统提示", "问题", new Object[]{new NoopTool()}, Map.of(),
                (iteration, toolName, summary) -> steps.add(iteration + ":" + summary));

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).contains("检索知识库").contains("事务失效");
    }
}
