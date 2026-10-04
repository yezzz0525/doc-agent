package com.example.docagent.service;

import com.example.docagent.dto.ChatResponse;
import com.example.docagent.dto.SourceRef;
import com.example.docagent.dto.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 问答服务：先判断问题类型，再决定"查文档回答"还是"直接聊"
 *
 * 两条路：
 *   技术问题   → RAG：检索相关片段 → 拼进提示词 → 让模型看着资料回答 → 附上引用来源
 *   闲聊/元问题 → 普通对话：不检索，直接把问题交给模型（例如"你是谁"）
 *
 * 这就是 RAG 的核心思想：不让模型凭记忆瞎猜，而是"开卷考试"——
 * 先帮它把相关材料翻出来放在面前，再让它看着材料回答。
 * 而路由的作用是：只在真正需要查资料的场合才翻书，别的问题正常聊天。
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 每次检索召回几个片段。调大：材料更全但噪音更多、更费 token；调小：更精准但可能漏 */
    private static final int TOP_K = 4;

    /**
     * 普通对话模式的提示词
     *
     * <h3>为什么这段提示词被改过两次</h3>
     * 第一版写的是"用户这句话<strong>与技术文档无关</strong>（可能是打招呼、问你的身份，或者只是在聊天）"，
     * 结果用户问"我想吃美食有什么推荐"时它就僵住了 —— 因为这句话把闲聊范围写得太窄，
     * 暗示"只有寒暄才算闲聊"。而实际上<b>模型自己是知道答案的</b>，不需要文档也能回答。
     *
     * <p>改的核心认知：<b>RAG 是"查资料"，不是"限制话题范围"</b>。
     * 这一轮不给资料，只是不查资料，不代表不能聊别的。
     *
     * <h3>这和"自由问答"的关系</h3>
     * 很多人以为要做"什么都能答的系统"得换模型、换架构，其实<b>模型的知识本来就够广</b>。
     * 真正需要工具的是"它自己不知道的东西"——今天的新闻、实时的菜价、你电脑里的文件。
     * 那属于 Tool Use 的范畴（见 README 的 Agent 路线），和提示词是两件事。
     */
    private static final String CHAT_PROMPT = """
            你是一个乐于助人的 AI 助手，正在和用户自由对话。

            这一轮不需要检索技术文档，所以：不涉及 Spring / Java 技术细节的问题，
            都可以直接基于你自己的知识回答——生活建议、美食推荐、学习方法、行程规划、
            打招呼、问你的身份、随便聊两句，都可以答。

            关于你自己：
            - 你叫 doc-agent。除了通用知识问答，你还有一个特长：
              遇到 Spring / Java 后端的技术问题，你会去翻官方文档再回答，并标出出处。
            - 用户问"你能做什么"时，除了通用问答，也提一句这个技术专长。

            联网能力：
            - 你**可以联网搜索**。遇到"今天/最新/最近"这类有时效性的问题
              （天气、新闻、价格、版本号、赛事、政策），一定要搜，不要凭记忆回答。
            - 搜索结果里可能有互相矛盾的旧信息，**优先采信时间更近的**，并在必要时说明"信息可能已过期"。

            要求：
            1. 用中文回答。闲聊就简短自然，别写小作文；用户认真提问就答详细些。
            2. 不要提"参考资料""文档库""检索"这些词——这一轮没查本地文档。
            3. **不要因为话题和技术无关就回避或把话题硬拉回技术。** 答不上来的直接说不知道，
               不要编造具体的信息（比如虚构餐厅名、虚构新闻）。
            4. **如果用户问的是技术问题**：你的判断可能出过错（这轮没走本地文档检索）。
               这时务必遵守两条——
               · 不确定就明说"这个我不确定，建议你查一下官方文档"，**绝不编造具体的类名、
                 注解、参数名、API 名称、版本号**，因为编错的技术细节比不回答危害更大；
               · 涉及 Spring 的问题，说明"我可以去翻 Spring 官方文档给你带出处的答案"，
                 引导用户换一句更明确的问法。
            5. **如果你用了联网搜索**：在回答**末尾**单独起一行，列出你参考到的网页链接，
               格式为「参考：[标题](链接)」，一行一条，最多 5 条。这让用户能自己核对信息来源。
            6. 用 Markdown 排版：要点的并列成 - 列表，代码和专有名词用反引号包起来，
               前面端会渲染成 nicer 的排版。
            """;

    private final ChatClient chatClient;
    /** 闲聊专用：开启了百炼的联网搜索（enable_search），能回答"今天天气"这类实时问题 */
    private final ChatClient webSearchChatClient;
    /** 知识库检索工具：让闲聊分支的模型自己决定要不要查文档 */
    private final DocSearchTool docSearchTool;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private final ConversationService conversationService;
    private final IntentRouter intentRouter;

    /** 记忆 Advisor：负责在每次调用模型前后，自动"读取历史"和"保存本轮对话" */
    private final MessageChatMemoryAdvisor memoryAdvisor;

    public RagChatService(ChatClient.Builder builder,
                          VectorStore vectorStore,
                          ChatMemory chatMemory,
                          ConversationService conversationService,
                          IntentRouter intentRouter,
                          DocSearchTool docSearchTool,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${doc-agent.web-search.enabled:true}") boolean webSearchEnabled,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${doc-agent.web-search.strategy:standard}") String webSearchStrategy) {
        this.docSearchTool = docSearchTool;
        this.chatClient = builder.build();
        this.webSearchChatClient = buildWebSearchClient(builder, webSearchEnabled, webSearchStrategy);
        this.vectorStore = vectorStore;
        this.chatMemory = chatMemory;
        this.conversationService = conversationService;
        this.intentRouter = intentRouter;
        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    /**
     * 构建「开启了联网搜索」的 ChatClient
     *
     * <h3>它解决什么问题</h3>
     * 模型的知识有截止日期，问它"今天杭州天气""最新 Spring 版本"它只能瞎猜。
     * 开启联网搜索后，百炼会自己去搜网页再回答 —— 这就是"什么都能问"里
     * 最容易补上的一块能力。
     *
     * <h3>怎么开启（Spring AI 2.0 的关键点）</h3>
     * 百炼的 {@code enable_search} <b>不是 OpenAI 标准参数</b>，官方要求放在请求体的
     * {@code extra_body} 里。好在 Spring AI 2.0 的 {@code OpenAiChatOptions}
     * 提供了 {@code extraBody(Map)}，可以直接把它塞进请求体，<b>不用自己造 HTTP 请求</b>。
     *
     * <pre>
     * OpenAiChatOptions.builder()
     *     .extraBody(Map.of("enable_search", true,
     *                      "search_options", Map.of("search_strategy", "standard")))
     * </pre>
     *
     * <h3>为什么用 clone() 而不是直接改 builder</h3>
     * RAG 分支<b>不能</b>开联网：它已经有本地知识库了，再让模型去搜网页，
     * 会出现"本地文档说 A、网上说 B"的矛盾，引用溯源也就失效了。
     * 所以要两个独立配置的客户端。{@code ChatClient.Builder.clone()} 让这件事很干净。
     *
     * <h3>成本提醒</h3>
     * 联网搜索会把搜到的网页正文塞进 prompt，<b>输入 token 会明显增加</b>（可能 5~10 倍）。
     * qwen-flash 输入 0.18 元/百万，按每天 30 次算，一个月也就几块钱，可以放心用。
     *
     * <h3>strategy 的区别</h3>
     * <ul>
     *   <li>{@code standard} —— 普通搜索，默认走这个，非流式也能用</li>
     *   <li>{@code agent_max} —— 会顺带抓取网页正文（web_extractor），
     *       信息更全，但百炼<b>明确不支持非流式输出</b>，只能在流式接口里用</li>
     * </ul>
     */
    private ChatClient buildWebSearchClient(ChatClient.Builder builder,
                                            boolean enabled, String strategy) {
        if (!enabled) {
            log.info("联网搜索未开启，普通对话将使用模型自身的知识（知识库工具仍可用）");
            return builder.clone().build();
        }
        Map<String, Object> searchOptions = new HashMap<>();
        searchOptions.put("enable_search", true);
        searchOptions.put("search_options", Map.of("search_strategy", strategy));

        try {
            ChatClient client = builder.clone()
                    .defaultOptions(OpenAiChatOptions.builder().extraBody(searchOptions))
                    .build();
            // ⚠️ 这里【不要】用 .defaultTools(docSearchTool) 挂工具 ——
            //    闲聊分支在 prompt 级别已经挂了一次（.tools(docSearchTool)），
            //    两处都挂的话 Spring AI 合并 ToolCallback 时会抛
            //    "Multiple tools with the same name (searchSpringDocs) found in ToolCallingChatOptions"。
            //    挂载点统一放在 prompt 级：闲聊挂、RAG 不挂，语义更清楚。
            log.info("联网搜索已开启（search_strategy={}）", strategy);
            return client;
        } catch (Exception e) {
            // 万一某个版本的 OpenAiChatOptions 构造器签名变了，降级成不联网而不是启动失败
            log.warn("开启联网搜索失败，本次降级为不联网（回答不会包含实时信息）：{}", e.getMessage());
            return builder.clone().build();
        }
    }

    /**
     * 提问
     *
     * @param conversationId 会话 ID，传空则新建一个会话
     * @param question       用户问题
     */
    public ChatResponse ask(String conversationId, String question) {
        String cid = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        // ===== 第 0 步：建档 + 恢复记忆 + 存档用户这句话 =====
        // 建档：保证这个会话在历史记录里存在（首次提问时才创建，避免列表里堆空壳）
        conversationService.ensure(cid);
        // 恢复记忆：如果应用重启过，ChatMemory 是空的，但磁盘上有历史——
        // 把历史读回 ChatMemory，用户点开三天前的会话还能接着追问。
        restoreMemoryIfNeeded(cid);
        // 存档用户这句话
        conversationService.append(cid, StoredMessage.user(question, now()));

        // ===== 第 1 步：路由 —— 判断该走哪条路 =====
        // 传 chatMemory.get(cid) 作为"上文"：这时它还只包含上一轮为止的内容
        // （本轮问题还没进 ChatMemory，它由 advisor 在调用模型时才写入），正好当上下文用
        IntentRouter.Intent intent = intentRouter.classify(question, chatMemory.get(cid));

        if (intent == IntentRouter.Intent.CHAT) {
            return answerChitchat(cid, question);
        }
        return answerWithRag(cid, question);
    }

    // ==================================================================
    // 路线一：普通对话（不查文档，直接调模型）
    // ==================================================================

    private ChatResponse answerChitchat(String cid, String question) {
        String answer = webSearchChatClient.prompt()
                .advisors(memoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .system(CHAT_PROMPT)
                .user(question)
                .tools(docSearchTool)
                .toolContext(Map.of("conversationId", cid == null ? "" : cid))
                .call()
                .content();

        if (answer == null || answer.isBlank()) {
            answer = "（模型这次没有返回内容，可以再说一次）";
        }

        // 同样取出工具检索到的引用来源，这样一次性接口和流式接口行为一致
        List<SourceRef> sources = docSearchTool.takeSources(cid);

        conversationService.append(cid,
                StoredMessage.assistant(answer, sources, ChatResponse.MODE_CHAT, now()));

        log.debug("会话 {} 走普通对话模式（引用来源 {} 条）", cid, sources.size());
        return sources.isEmpty()
                ? ChatResponse.chat(cid, answer)
                : ChatResponse.chatWithSources(cid, answer, sources);
    }

    // ==================================================================
    // 路线二：RAG（检索文档 → 看着资料回答 → 带引用）
    // ==================================================================

    private ChatResponse answerWithRag(String cid, String question) {
        // ===== 第 2 步：检索（检索 + 拼提示词 + 整理引用，都收在 prepareRag 里）=====
        RagPlan plan = prepareRag(cid, question);

        if (plan == null) {
            // 知识库空时直接返回，不调模型——这样也避免把"查不到"写进对话记忆
            String text = "知识库还是空的，或者没有找到相关内容。请先在左侧点「加载文档」建立索引，再提问。";
            conversationService.append(cid,
                    StoredMessage.assistant(text, List.of(), ChatResponse.MODE_RAG, now()));
            return ChatResponse.rag(cid, text, List.of());
        }

        // ===== 第 5 步：调用模型（带对话记忆）=====
        long genStart = System.nanoTime();
        String answer = chatClient.prompt()
                .advisors(memoryAdvisor)
                // 指定本次对话属于哪个会话——不同会话的记忆互不干扰
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .system(plan.systemPrompt())
                .user(question)
                .call()
                .content();
        log.info("会话 {} 模型生成完毕，耗时 {} ms，正文 {} 字", cid,
                (System.nanoTime() - genStart) / 1_000_000, answer == null ? 0 : answer.length());

        if (answer == null || answer.isBlank()) {
            answer = "（模型这次没有返回内容，可以换个问法再试一次）";
        }

        // ===== 第 6 步：存档模型这句话 =====
        conversationService.append(cid,
                StoredMessage.assistant(answer, plan.sources(), ChatResponse.MODE_RAG, now()));

        return ChatResponse.rag(cid, answer, plan.sources());
    }

    /**
     * RAG 的准备工作：检索 → 整理引用来源 → 拼好提示词
     *
     * 抽出来的原因：一次性回答（ask）和流式回答（streamAsk）都要用这段，
     * 写两份迟早会改漏一份。返回 null 表示知识库里没有可用内容。
     */
    private RagPlan prepareRag(String cid, String question) {
        // ===== 耗时打点：回答慢的时候，日志里能直接看出慢在哪一环 =====
        // 一次 RAG 问答要串行走三段外部调用：意图分类 → 向量检索 → 生成。
        // 界面上只是"转圈"，但没有日志就完全不知道瓶颈在哪，这里把每段耗时都记下来。
        long t0 = System.nanoTime();

        // 把查询词转成向量，去向量库里找语义最相近的几个片段
        String searchQuery = buildSearchQuery(cid, question);
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(searchQuery)
                        .topK(TOP_K)
                        .build());
        long searchMs = (System.nanoTime() - t0) / 1_000_000;

        if (hits == null || hits.isEmpty()) {
            return null;
        }

        List<SourceRef> sources = new ArrayList<>(hits.size());
        for (Document doc : hits) {
            String file = String.valueOf(doc.getMetadata().getOrDefault("source", "未知来源"));
            Object title = doc.getMetadata().get("title");
            sources.add(SourceRef.of(file, title == null ? null : String.valueOf(title),
                    doc.getScore(), doc.getText()));
        }

        String context = hits.stream()
                .map(doc -> "【来源: " + doc.getMetadata().getOrDefault("title",
                        doc.getMetadata().getOrDefault("source", "未知")) + "】\n" + doc.getText())
                .collect(Collectors.joining("\n\n——————\n\n"));

        // 关键约束：只许根据资料回答，不许编造——这是压制幻觉的核心手段。
        // 另外明确要求用 Markdown 排版，因为前端会按 Markdown 渲染，排版好不好直接决定观感。
        String systemPrompt = """
                你是一个 Spring 技术文档助手。请严格根据下面的参考资料回答用户问题。
                如果资料中没有相关内容，请直接回答"文档资料中没有找到相关内容"，不要凭空编造。
                用户可能会追问上一轮的内容，请结合对话历史理解他在问什么。

                回答要求：
                1. 用中文回答，语气专业但平易近人。
                2. 使用 Markdown 排版：小节用 ### 标题，关键配置项用 `代码` 包裹，
                   配置示例或代码用 ``` 代码块，并列的要点用 - 列表。
                3. 不要罗列文件名，也不要写"根据参考资料"，系统会自动展示引用来源。
                4. 回答长度适中，直接回答问题本身，不要展开无关内容。

                参考资料：
                %s
                """.formatted(context);

        // 命中 4 段资料时，拼出来的提示词大约 3000+ token。模型读得越慢、出字越慢，
        // 这是"转圈很久"的第二个常见原因（第一个是意图分类那多出来的一次调用）。
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("会话 {} 检索完成：命中 {} 段，向量检索耗时 {} ms，资料拼装 {} ms（共 {} 字），检索词：{}",
                cid, hits.size(), searchMs, totalMs - searchMs,
                context.length(), searchQuery);
        return new RagPlan(systemPrompt, sources);
    }

    /** 检索结果打包：拼好的提示词 + 引用来源 */
    private record RagPlan(String systemPrompt, List<SourceRef> sources) {}

    // ==================================================================
    // 流式版本：回答一个字一个字往外推
    // ==================================================================

    /**
     * 提问（流式版）
     *
     * 和非流式的 ask() 做的事完全一样，区别只在最后一步：
     *   ask()      —— 等模型把整段话想完，一次性返回
     *   streamAsk()—— 模型想到哪儿就往外推到哪儿，前端能立刻看到字在蹦
     *
     * 用户体验差别很大：长回答要十几秒，一次性返回的话用户盯着空白干等，
     * 很容易以为卡死了。这也是所有 AI 产品都做打字机效果的原因。
     */
    public void streamAsk(String conversationId, String question, StreamSink sink) {
        String cid = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        conversationService.ensure(cid);
        restoreMemoryIfNeeded(cid);
        conversationService.append(cid, StoredMessage.user(question, now()));

        IntentRouter.Intent intent = intentRouter.classify(question, chatMemory.get(cid));

        if (intent == IntentRouter.Intent.CHAT) {
            streamChitchat(cid, question, sink);
        } else {
            streamWithRag(cid, question, sink);
        }
    }

    /** 流式 · 普通对话 */
    private void streamChitchat(String cid, String question, StreamSink sink) {
        // 元信息先发出去：告诉前端会话 ID 和"这次没查文档"，正文随后就到
        sink.onMeta(ChatResponse.chat(cid, ""));

        StringBuilder buffer = new StringBuilder();

        // 注意用的是 webSearchChatClient —— 只有闲聊分支开联网搜索，
        // RAG 分支保持纯本地知识库，否则会出现"本地文档说 A、网上说 B"的矛盾
        webSearchChatClient.prompt()
                .advisors(memoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .system(CHAT_PROMPT)
                .user(question)
                // 挂知识库工具 + 传会话 ID 进去（工具要靠它把引用来源暂存回来）
                .tools(docSearchTool)
                .toolContext(Map.of("conversationId", cid == null ? "" : cid))
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            buffer.append(chunk);
                            sink.onToken(chunk);
                        },
                        err -> {
                            log.error("会话 {} 普通对话流式失败", cid, err);
                            sink.onError(err.getMessage());
                        },
                        () -> {
                            // 模型可能在这一轮里调用了知识库工具，把引用来源补发给前端。
                            // 这一步是"工具返回值（文本）→ 结构化引用卡片"的关键桥梁。
                            List<SourceRef> sources = docSearchTool.takeSources(cid);
                            if (!sources.isEmpty()) {
                                log.info("会话 {} 本轮经工具检索到 {} 段资料，已推送引用来源", cid, sources.size());
                                sink.onSources(sources);
                            }
                            finishStream(cid, buffer, sources, ChatResponse.MODE_CHAT, sink);
                        });

        log.debug("会话 {} 走流式普通对话模式", cid);
    }

    /** 流式 · RAG */
    private void streamWithRag(String cid, String question, StreamSink sink) {
        RagPlan plan = prepareRag(cid, question);

        if (plan == null) {
            String text = "知识库还是空的，或者没有找到相关内容。请先在左侧点「加载文档」建立索引，再提问。";
            conversationService.append(cid,
                    StoredMessage.assistant(text, List.of(), ChatResponse.MODE_RAG, now()));
            sink.onMeta(ChatResponse.rag(cid, "", List.of()));
            sink.onToken(text);
            sink.onComplete();
            return;
        }

        // 引用来源先到，正文后到：前端可以在回答还在蹦的时候就展示"参考了哪些文档"
        sink.onMeta(ChatResponse.rag(cid, "", plan.sources()));

        StringBuilder buffer = new StringBuilder();

        // 首字延迟（TTFT）打点：流式体验好不好，关键就在"从提问到第一个字出现"等了多久。
        // 如果这个值很大而"总耗时"不大，说明模型一次性想太久才吐字，
        // 提示词太长（4 段资料 + 20 条历史）是主要嫌疑。
        long genStart = System.nanoTime();
        long[] firstTokenMs = {-1};

        chatClient.prompt()
                .advisors(memoryAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .system(plan.systemPrompt())
                .user(question)
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            if (firstTokenMs[0] < 0) {
                                firstTokenMs[0] = (System.nanoTime() - genStart) / 1_000_000;
                            }
                            buffer.append(chunk);
                            sink.onToken(chunk);
                        },
                        err -> {
                            log.warn("会话 {} RAG 流式失败（已推 {} 段，共等 {} ms，首字延迟 {} ms）。"
                                            + "若是 429，说明免费模型限速了，等待片刻重试即可",
                                    cid, buffer.length(), (System.nanoTime() - genStart) / 1_000_000,
                                    firstTokenMs[0]);
                            log.error("失败详情", err);
                            // 在错误消息里标出阶段，用户一眼就知道该看哪块，不用去猜
                            // plan 为 null 表示连资料都没检索到，否则说明检索已过、挂在生成阶段
                            String phase = (plan == null) ? "向量检索" : "回答生成";
                            sink.onError("【" + phase + "阶段】" + err.getMessage());
                        },
                        () -> {
                            log.info("会话 {} 流式完成：首字延迟 {} ms，总耗时 {} ms，输出 {} 字",
                                    cid, firstTokenMs[0], (System.nanoTime() - genStart) / 1_000_000,
                                    buffer.length());
                            finishStream(cid, buffer, plan.sources(), ChatResponse.MODE_RAG, sink);
                        });
    }

    /**
     * 流式结束的收尾：把完整答案存档（历史记录里要留全的，不能存半截）
     *
     * 注意这里没有手动往 ChatMemory 里写记忆——因为 MessageChatMemoryAdvisor
     * 在流式模式下也会自动做这件事（它内部有个聚合器，边转发边把整段回答攒起来，
     * 流结束时一次性写入）。所以流式和非流式的记忆逻辑是一致的，不用写两套。
     */
    private void finishStream(String cid, StringBuilder buffer, List<SourceRef> sources,
                              String mode, StreamSink sink) {
        String answer = buffer.toString();
        if (answer.isBlank()) {
            answer = "（模型这次没有返回内容，可以换个问法再试一次）";
            sink.onToken(answer);
        }
        conversationService.append(cid, StoredMessage.assistant(answer, sources, mode, now()));
        sink.onComplete();
    }

    /**
     * 从磁盘上的历史记录，把对话记忆恢复到 ChatMemory 里
     *
     * 为什么需要这一步？
     * ChatMemory 存在内存中，应用一重启就空了。用户点开昨天的会话继续追问时，
     * 模型看不到上文，会把"那证书怎么续期"当成一个孤立的问题——就答非所问了。
     *
     * 判断依据：如果 ChatMemory 里已经有内容，说明本次运行已经记着这个会话，
     * 说明不用恢复（避免重复灌入）。
     */
    private void restoreMemoryIfNeeded(String conversationId) {
        List<Message> existing = chatMemory.get(conversationId);
        if (existing != null && !existing.isEmpty()) {
            return;
        }

        List<StoredMessage> stored = conversationService.messages(conversationId);
        if (stored.isEmpty()) {
            return;
        }

        List<Message> restored = new ArrayList<>(stored.size());
        for (StoredMessage m : stored) {
            if (m.text() == null || m.text().isBlank()) {
                continue;
            }
            restored.add("user".equals(m.role())
                    ? new UserMessage(m.text())
                    : new AssistantMessage(m.text()));
        }

        if (!restored.isEmpty()) {
            chatMemory.add(conversationId, restored);
            log.info("已从历史记录恢复会话 {} 的记忆，共 {} 条消息", conversationId, restored.size());
        }
    }

    /**
     * 构造检索用的查询词
     *
     * 为什么不能直接用当前问题？因为多轮对话里的追问往往是不完整的句子：
     *   第 1 轮："Spring Boot 怎么配置 SSL？"
     *   第 2 轮："那证书怎么自动续期？"   ← 单看这句，向量检索根本不知道"证书"指什么
     * 所以把上一轮的问题拼进来一起检索，命中率会明显提高。
     *
     * 更专业的做法是用大模型改写查询（Spring AI 的 RewriteQueryTransformer 就是干这个的），
     * 代价是每次提问多花一次模型调用。当前这种"拼接"是零成本的近似方案，够用。
     */
    private String buildSearchQuery(String conversationId, String question) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return question;
        }

        String lastUserText = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof UserMessage userMsg) {
                lastUserText = userMsg.getText();
                break;
            }
        }

        if (lastUserText == null || lastUserText.isBlank()) {
            return question;
        }
        return lastUserText + " " + question;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
