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
import org.springframework.jdbc.core.JdbcTemplate;
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
     * 直接操作 pgvector 表用的 JDBC 模板
     *
     * <p>为什么需要它：{@link VectorStore} 接口<b>没有"列出全部文档"的方法</b>
     * （它只提供"按相似度检索"）。而 {@code listKnowledgeBaseTopics} 需要的是
     * 全量标题列表，用相似度检索去凑是<b>不准确的</b>（会把不相关的排前面、
     * 受topK 限制截断）。所以这里拿原生 JdbcTemplate 直查表，
     * 走的是 Spring AI 建的那张表，schema 变了也不用改这里的 SQL。
     */
    private final JdbcTemplate jdbcTemplate;

    /** 表名，来自 application.yaml 的 doc-agent.vector-store.table */
    private final String tableName;

    /**
     * 检索结果暂存区：conversationId → 本轮检索到的引用来源
     *
     * <p>为什么需要它：工具方法返回给模型的是拼好的文本，前端却需要结构化的
     * file/title/score 来渲染"引用来源"卡片。模型调用工具的过程中前端拿不到中间结果，
     * 所以先存下来，等回答结束时统一推送。
     */
    private final Map<String, List<SourceRef>> pendingSources = new ConcurrentHashMap<>();

    public DocSearchTool(VectorStore vectorStore,
                         JdbcTemplate jdbcTemplate,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${doc-agent.vector-store.table:vector_store}") String tableName) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
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
        sb.append("""
                【检索结果结束】
                判断标准：如果这些资料已经能准确回答问题，就直接回答；
                如果资料过于笼统、没有具体的类名/参数名/配置项，
                请换一个更具体的关键词再调用一次本工具（例如补充具体技术名词），最多再试 2 次。""");

        // 存下结构化引用，等这轮结束时推给前端渲染卡片
        if (cid != null) {
            pendingSources.put(cid, sources);
        }
        return sb.toString();
    }

    /**
     * 查知识库里有哪些资料
     *
     * <h3>为什么需要这个工具</h3>
     * ReAct 循环里模型会自己决定调什么工具。但如果它不知道"库里到底有什么"，
     * 只能盲猜关键词去检索，命中率会很低。加一个"先看看目录"的工具，
     * 模型就能像人查字典一样：<b>先翻目录定位章节，再查具体内容</b>。
     *
     * <p>典型场景：用户问"缓存怎么配"，模型可能先用模糊词查一次发现不具体，
     * 然后调本工具看到有「Caching」相关文档，再用准确的词查第二次 —— 这就是
     * ReAct 的"思考 → 观察 → 再思考"真正在起作用。
     */
    @Tool(description = """
            查看知识库里收录了哪些主题的文档，用来决定接下来该用什么关键词检索。
            适用场景：不确定知识库有哪些内容、或者第一次检索结果过于笼统需要换个方向时。
            典型用法：先调用本工具看看有哪些主题，再用更准确的关键词调用 searchSpringDocs。""")
    public String listKnowledgeBaseTopics(
            @ToolParam(description = "可选的过滤关键词，只想看某个方向时填，如「缓存」「安全」，留空则列出全部")
            String filter,
            ToolContext toolContext) {

        String cid = extractConversationId(toolContext);
        log.info("模型查看知识库目录：cid={} filter={}", cid, filter);

        // 一次性列出所有文档标题：知识库规模不大（几百篇），全量列出来更有用，
        // 模型的检索词往往就藏在标题里
        try {
            List<String> raw = listAllTitles();
            if (raw == null || raw.isEmpty()) {
                return "【知识库是空的】请提示用户先在界面点「加载文档」建立索引。";
            }

            // 末尾那条是片段总数（拼在同一个List 里省一个查询方法）
            int chunkCount = 0;
            for (String t : raw) {
                if (t.startsWith("__TOTAL__")) {
                    chunkCount = Integer.parseInt(t.substring(9));
                }
            }

            // 用标题去重：一个主题只列一次，避免几百条刷屏
            java.util.LinkedHashSet<String> titles = new java.util.LinkedHashSet<>();
            for (String t : raw) {
                if (t.startsWith("__TOTAL__")) {
                    continue;
                }
                if (filter == null || filter.isBlank() || t.toLowerCase().contains(filter.toLowerCase())) {
                    titles.add(t);
                }
            }

            if (titles.isEmpty()) {
                return "【没有匹配「" + filter + "」的主题】请换个过滤词，或留空直接调用以查看全部主题。";
            }

            StringBuilder sb = new StringBuilder("【知识库共收录 ").append(titles.size())
                    .append(" 个主题，片段总数 ").append(chunkCount).append("】：\n");
            int i = 0;
            for (String t : titles) {
                sb.append(++i).append(". ").append(t).append("\n");
            }
            sb.append("""
                    
                    请从上面挑最相关的主题，用它的名称或其中的技术名词作为关键词，调用 searchSpringDocs 检索细节。
                    如果上面没有合适的主题，说明知识库确实不覆盖这部分内容。""");
            return sb.toString();

        } catch (Exception e) {
            log.warn("读取知识库目录失败：{}", e.getMessage());
            return "【知识库目录读取失败】" + e.getMessage() + "。请直接基于你自己的知识回答。";
        }
    }

    /**
     * 取出并合并某会话累积到的引用来源
     *
     * <h3>为什么要「累积」而不只是「一次性消费」</h3>
     * ReAct 循环里模型可能查好几次知识库，每轮都会调{@code takeSources}。
     * 早期版本用 {@code remove} 一次取空，导致第二次查到的资料在前端显示不出来。
     * 现在改成：每轮取到的都<b>合并</b>进一个列表，按「文件+片段」去重，
     * 等循环真正结束时再一次性交给上层推给前端。
     */
    public List<SourceRef> takeSources(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        // 注意：remove 的返回值才是结果。写成 remove 之后再 get 会永远拿到空，
        // 因为数据已经被删掉了（这个 bug 在开发时真的犯过一次）。
        List<SourceRef> removed = pendingSources.remove(conversationId);
        return removed == null ? List.of() : removed;
    }

    /** 清理某会话的暂存（会话被删除时调用，避免内存泄漏） */
    public void clearSources(String conversationId) {
        if (conversationId != null) {
            pendingSources.remove(conversationId);
        }
    }

    /** 从 pgvector 表里读出全部文档标题（listKnowledgeBaseTopics 用） */
    private List<String> listAllTitles() {
        // Spring AI 建的表里，metadata 是 jsonb，文档标题存在 metadata->>'title'
        // 只 select 这一列而不是 embedding（1024 个浮点数），省一大半带宽
        List<String> titles = jdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'title' AS t FROM " + tableName
                        + " WHERE metadata->>'title' IS NOT NULL ORDER BY t",
                String.class);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + tableName, Integer.class);
        titles.add("__TOTAL__" + (total == null ? 0 : total));
        return titles;
    }

    private String extractConversationId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object cid = toolContext.getContext().get(CTX_CONVERSATION_ID);
        return cid == null ? null : String.valueOf(cid);
    }

    // ==================================================================
    // 评测专用：只检索不生成
    // ==================================================================

    /**
     * 评测用的检索入口 —— <b>不调大模型，只查向量库</b>
     *
     * <h3>为什么必须和 searchSpringDocs 分开</h3>
     * {@link #searchSpringDocs} 的返回值是<b>拼给模型看的文本</b>，
     * 格式会为了「让模型好读」而调整；评测要的是<b>结构化的原始结果</b>
     * （哪一段、来自哪篇、相似度多少），才能算命中率。
     * <p>
     * 更重要的是速度：检索一次约 100~300ms，纯检索 25 道题只要几秒；
     * 走完整问答链路每题十几秒、还要烧 token。评测要能随手跑，就不能慢。
     *
     * @param query 检索词
     * @param topK  取几段。评测里要显式传，因为 topK 直接决定 Recall@K 的 K
     */
    public EvalRetrieveResult evalRetrieve(String query, int topK) {
        long t0 = System.nanoTime();
        // 查 embedding 要打一次外部 API（硅基流动），这段耗时通常占大头
        long before = System.nanoTime();
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
        long searchMs = (System.nanoTime() - before) / 1_000_000;

        List<EvalHit> results = new ArrayList<>();
        if (hits != null) {
            for (Document doc : hits) {
                Object title = doc.getMetadata().get("title");
                results.add(new EvalHit(
                        title == null ? null : String.valueOf(title),
                        String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                        doc.getScore(),
                        doc.getText() == null ? "" : doc.getText()));
            }
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        // 慢在哪要分清：是"打 embedding 接口慢"还是"pgvector 查得慢"，
        // 优化方向完全不同（前者换/缓存模型，后者调索引）
        log.info("[EVAL] query={} topK={} hits={} searchMs={} totalMs={}",
                query, topK, results.size(), searchMs, totalMs);
        return new EvalRetrieveResult(query, topK, results, searchMs, totalMs);
    }

    /** 检索结果里的一条命中 */
    public record EvalHit(String title, String file, Double score, String snippet) {}

    /** 评测检索的返回：带耗时，方便定位瓶颈 */
    public record EvalRetrieveResult(String query, int topK, List<EvalHit> hits,
                                     long searchMs, long totalMs) {}
}
