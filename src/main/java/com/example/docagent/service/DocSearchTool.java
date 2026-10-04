package com.example.docagent.service;

import com.example.docagent.dto.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「知识库检索」包装成一个工具，让模型自己决定要不要查
 *
 * <h3>为什么要这么做（这是本项目最关键的一次架构升级）</h3>
 * 改造之前是<b>意图路由</b>：我写 if-else 先判断问题类型，再决定走 RAG 还是闲聊。
 * <pre>
 *   旧：用户问 → IntentRouter 判断 → 是技术问题？→ 是：强制检索 4 段丢给模型
 *                                          → 否：直接聊
 * </pre>
 * 这套做法有个硬伤：<b>判断权在我手上，不在模型手上</b>。
 * 关键词表覆盖不到的问法就会被误判（用户问「有什么好吃的美食」曾被判成技术问题，
 * 结果硬去查 Spring 文档）；反过来漏判又会白白多查一次。
 *
 * <p>改造之后是<b>工具调用</b>：我只负责把「查知识库」这个能力递给模型，
 * <b>用不用、什么时候用、查什么关键词，全由模型自己决定</b>：
 * <pre>
 *   新：用户问 → 模型判断需不需要查 → 需要：自己调 searchSpringDocs("SSL 配置")
 *                                → 不需要：直接回答（该联网就联网，该闲聊就闲聊）
 * </pre>
 *
 * <p><b>这一步的意义</b>：出口从「两种」变成「N 种」（查文档 / 联网 / 闲聊 / 未来的更多工具），
 * 决策者从我写死的 if-else 换成模型 —— <b>这就是 Agent 和问答系统的分水岭</b>。
 *
 * <h3>那还需要意图路由吗</h3>
 * 需要，但角色变了：它降级成「<b>优化器</b>」而不是「决策器」。
 * 技术问题走 RAG 分支可以拿到更准的检索词和更严格的防幻觉提示词；
 * 而闲聊分支现在多了这个工具，模型照样能查知识库 —— 两条路都能查，只是策略不同。
 *
 * <h3>引用溯源怎么在工具模式下保住</h3>
 * 工具返回给模型的是<b>纯文本</b>（模型只认文本），但前端要的是<b>结构化引用卡片</b>。
 * 这里用 {@code ToolContext} 把会话 ID 传进工具，工具检索完顺手把结构化的
 * {@link SourceRef} 存进一个以会话 ID 为键的暂存区；等这一轮回答结束时，
 * {@code RagChatService} 取出来通过 SSE 的 sources 事件推给前端。
 *
 * <h3>为什么用 ConcurrentHashMap 而不是 ThreadLocal</h3>
 * 流式响应式执行时回调可能不在同一个线程上，ThreadLocal 会取不到值。
 * 而以 conversationId 为键的 Map 是线程无关的，最稳。用完立刻 remove，不留垃圾。
 */
@Component
public class DocSearchTool {

    private static final Logger log = LoggerFactory.getLogger(DocSearchTool.class);

    private static final String CTX_CONVERSATION_ID = "conversationId";

    /** 每次检索召回几个片段，和 RAG 分支保持一致 */
    private static final int TOP_K = 4;

    private final VectorStore vectorStore;

    /**
     * 检索结果暂存区：conversationId → 本轮检索到的引用来源
     *
     * <p>为什么需要它：工具方法返回给模型的是拼好的文本，前端却需要结构化的
     * file/title/score 来渲染"引用来源"卡片。模型调用工具的过程中前端拿不到中间结果，
     * 所以先存下来，等回答结束时统一推送。
     */
    private final Map<String, List<SourceRef>> pendingSources = new ConcurrentHashMap<>();

    public DocSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 查询 Spring 知识库
     *
     * @param query       查询关键词，由模型自己从用户问题里提炼
     * @param toolContext 框架注入的上下文，里面有我们放的 conversationId
     * @return 拼给模型看的资料文本
     */
    @Tool(description = """
            查询 Spring 官方文档知识库，返回最相关的若干段落原文。
            适用场景：用户的问题涉及 Spring / Java 后端的具体用法、配置、API、报错含义。
            不需要查询的场景：闲聊、常识、生活建议、以及任何与 Spring 技术无关的问题。
            只在确实需要查官方文档以确保准确性时才调用，不要凭记忆回答技术细节。""")
    public String searchSpringDocs(
            @ToolParam(description = "搜索关键词，用中文短语，例如「SSL 配置」「自定义 Starter」「事务失效」")
            String query,
            ToolContext toolContext) {

        String cid = extractConversationId(toolContext);
        log.info("模型调用知识库工具：cid={} query={}", cid, query);

        List<Document> hits;
        try {
            hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .build());
        } catch (Exception e) {
            // 检索失败不能让整个对话挂掉，返回一句说明让模型用自己的知识答
            log.warn("知识库检索失败（{}），将让模型回退到自身知识回答", e.getMessage());
            return "【知识库暂时不可用】请直接基于你自己的知识回答，并提示用户可以稍后重试。";
        }

        if (hits == null || hits.isEmpty()) {
            return "【知识库里没有检索到相关内容】请基于你自己的知识回答，并提示用户知识库中暂无这部分资料。";
        }

        List<SourceRef> sources = new ArrayList<>(hits.size());
        StringBuilder sb = new StringBuilder("【知识库检索结果】共 ").append(hits.size()).append(" 段：\n");
        for (Document doc : hits) {
            String file = String.valueOf(doc.getMetadata().getOrDefault("source", "未知来源"));
            Object titleObj = doc.getMetadata().get("title");
            String title = titleObj == null ? file : String.valueOf(titleObj);
            sources.add(SourceRef.of(file, title, doc.getScore(), doc.getText()));
            sb.append("\n【").append(title).append("】（来源文件：").append(file).append("）\n")
                    .append(doc.getText()).append("\n——————\n");
        }
        sb.append("【检索结果结束】请严格基于以上资料回答，并在回答末尾用「参考：[标题](来源文件)」列出你用到的资料。");

        // 存下结构化引用，等这轮结束时推给前端渲染卡片
        if (cid != null) {
            pendingSources.put(cid, sources);
        }
        return sb.toString();
    }

    /** 取出并清除某会话本轮检索到的引用来源（一次性消费） */
    public List<SourceRef> takeSources(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        // 注意：remove 的返回值才是结果。写成 remove 之后再 get 会永远拿到空，
        // 因为数据已经被删掉了（这个 bug 在开发时真的犯过一次）。
        List<SourceRef> removed = pendingSources.remove(conversationId);
        return removed == null ? List.of() : removed;
    }

    private String extractConversationId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object cid = toolContext.getContext().get(CTX_CONVERSATION_ID);
        return cid == null ? null : String.valueOf(cid);
    }
}
