package com.example.docagent.controller;

import com.example.docagent.dto.ChatRequest;
import com.example.docagent.dto.ChatResponse;
import com.example.docagent.dto.Conversation;
import com.example.docagent.dto.ConversationSummary;
import com.example.docagent.dto.IndexStatus;
import com.example.docagent.dto.LoadResult;
import com.example.docagent.dto.RenameRequest;
import com.example.docagent.dto.TokenUsage;
import com.example.docagent.service.ConversationService;
import com.example.docagent.service.DocSearchTool;
import com.example.docagent.service.DocumentService;
import com.example.docagent.service.RagChatService;
import com.example.docagent.service.TokenUsageService;
import com.example.docagent.service.StreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 给前端（Vue）用的 REST 接口
 *
 * 和 DocController 的区别：
 * - DocController 返回纯文本，方便在浏览器地址栏直接调，用于调试
 * - 这里返回 JSON，供前端页面渲染
 *
 * 接口一览：
 *   ---- 知识库 ----
 *   GET    /api/status                     知识库状态
 *   POST   /api/load                       加载文档建立索引
 *   ---- 问答 ----
 *   POST   /api/chat                       提问（支持多轮，带引用来源）
 *   POST   /api/chat/stream                提问（流式，逐字返回，SSE）
 *   ---- 会话管理（左侧边栏）----
 *   GET    /api/conversations              会话列表（摘要，不含消息正文）
 *   GET    /api/conversations/{id}         某个会话的完整内容
 *   PATCH  /api/conversations/{id}         重命名
 *   DELETE /api/conversations/{id}         删除
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")   // demo 阶段放开跨域，方便前端直连
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    /**
     * 自己 new 一个 ObjectMapper，不用注入的：
     * Spring Boot 4 同时带着 Jackson 2 和 Jackson 3 两套，注入容易撞车。
     * 这里只用来把事件内容转成 JSON 字符串，独立用一个最省心。
     */
    private final ObjectMapper mapper = new ObjectMapper();

    private final DocumentService documentService;
    private final RagChatService ragChatService;
    private final ConversationService conversationService;
    private final TokenUsageService tokenUsageService;
    private final ChatMemory chatMemory;
    private final DocSearchTool docSearchTool;

    public ApiController(DocumentService documentService,
                         RagChatService ragChatService,
                         ConversationService conversationService,
                         TokenUsageService tokenUsageService,
                         ChatMemory chatMemory,
                         DocSearchTool docSearchTool) {
        this.documentService = documentService;
        this.ragChatService = ragChatService;
        this.conversationService = conversationService;
        this.tokenUsageService = tokenUsageService;
        this.chatMemory = chatMemory;
        this.docSearchTool = docSearchTool;
    }

    // ==================== 知识库 ====================

    /** 知识库状态：前端一打开就问一次，用来显示"有多少内容可问" */
    @GetMapping("/status")
    public IndexStatus status() {
        return documentService.status();
    }

    /**
     * token 用量统计（本次应用运行期间）
     *
     * 免费模型没有账单可查，但"我到底用了多少"仍然需要看得见。
     * 这个接口把每次调用返回的 usage 累加起来，前端显示在侧边栏底部。
     */
    @GetMapping("/usage")
    public TokenUsage usage() {
        return tokenUsageService.snapshot();
    }

    /** 加载文档建立索引。maxFiles 留空表示全部加载 */
    @PostMapping("/load")
    public LoadResult load(@RequestParam(defaultValue = "tools/spring-docs") String dir,
                           @RequestParam(required = false) Integer maxFiles) throws IOException {
        return documentService.loadDocs(dir, maxFiles == null ? Integer.MAX_VALUE : maxFiles);
    }

    // ==================== 评测 ====================

    /**
     * 只检索、不生成 —— 给评测脚本用的"尺子"接口
     *
     * <h3>为什么必须有这个接口</h3>
     * 评测要分两层测：
     * <pre>
     *   检索层：给的资料对不对？   ← 这一层不该调模型，否则又慢又花钱
     *   生成层：基于对的资料答得好不好？
     * </pre>
     * 如果只有一个 /api/chat，每次评测都得跑完整条ReAct 链路：
     * 25 道题 × 十几秒 = 好几分钟，而且每跑一次都烧 token。
     * 有了这个接口，检索层评测<b>零成本、零延迟</b>，可以随手跑。
     *
     * <h3>用法</h3>
     * <pre>
     * GET /api/eval/retrieve?query=事务失效&amp;topK=4
     * </pre>
     */
    @GetMapping("/eval/retrieve")
    public DocSearchTool.EvalRetrieveResult evalRetrieve(@RequestParam String query,
                                                         @RequestParam(defaultValue = "4") int topK) {
        return docSearchTool.evalRetrieve(query, topK);
    }

    // ==================== 问答 ====================

    /** 提问（一次性返回整段回答） */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return ragChatService.ask(request.conversationId(), request.question());
    }

    /**
     * 提问（流式版，SSE）
     *
     * SSE = Server-Sent Events，服务端主动往浏览器"推"数据的一条长连接。
     * 和普通的 /api/chat 区别只有一点：那个等模型想完整段话再返回，
     * 这个模型想到哪儿推到哪儿，前端收到一个字就显示一个字（打字机效果）。
     *
     * 事件格式（每行以 \n\n 结尾）：
     *   event: meta  → { conversationId, mode, sources }   最先到，只有一次
     *   event: token → "一小段文字"                        中间来很多次
     *   event: done  → "ok"                              最后一次，表示结束
     *   event: error → "错误原因"                         出错时替代 done
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        // 3 分钟足够长回答 + 慢模型；超时会自动关闭，前端按"结束"处理
        SseEmitter emitter = new SseEmitter(180_000L);

        emitter.onTimeout(() -> {
            log.warn("流式输出超时，关闭连接");
            safeComplete(emitter);
        });
        emitter.onError(e -> log.debug("流式连接异常（用户可能刷新了页面）：{}", e.getMessage()));

        ragChatService.streamAsk(request.conversationId(), request.question(), new StreamSink() {
            @Override
            public void onMeta(ChatResponse meta) {
                emit("meta", meta);
            }

            @Override
            public void onToken(String text) {
                emit("token", text);
            }

            /**
             * Tool Use 模式下，模型先调用知识库工具、拿到结果才开始回答，
             * 所以引用来源在这时才确定 —— 单独发一个事件补送给前端。
             */
            @Override
            public void onSources(java.util.List<com.example.docagent.dto.SourceRef> sources) {
                emit("sources", sources);
            }

            /**
             * ReAct 循环的进度提示。
             *
             * <p>模型要先调工具才写正文，中间有停顿。发这个事件让前端显示
             * "正在检索…"，用户就不会以为卡死了。
             */
            @Override
            public void onStep(int iteration, String summary) {
                emit("step", java.util.Map.of("iteration", iteration, "summary", summary));
            }

            @Override
            public void onError(String message) {
                emit("error", message == null ? "未知错误" : message);
                safeComplete(emitter);
            }

            @Override
            public void onComplete() {
                emit("done", "ok");
                safeComplete(emitter);
            }

            /**
             * 所有数据统一用 JSON 编码再发送
             *
             * 为什么不能直接发原文？因为正文里必然有换行，而 SSE 协议用"空行"
             * 表示一个事件的结束——直接发原文会被客户端误切成好几个事件。
             * JSON 会把换行转成 \n，就安全了。
             */
            private void emit(String event, Object data) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event)
                            .data(mapper.writeValueAsString(data)));
                } catch (Exception e) {
                    log.warn("推送 {} 事件失败（客户端可能已断开）：{}", event, e.getMessage());
                    safeComplete(emitter);
                }
            }
        });

        return emitter;
    }

    /** 关闭 SSE 连接。重复关闭会抛异常，这里吞掉——用户刷新页面时很常见 */
    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已经关过了，忽略
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 会话列表
     *
     * 只返回摘要（标题、时间、条数），不返回消息正文——
     * 侧边栏只要这些东西，聊得多的时候能省下大量传输。
     */
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        return conversationService.list();
    }

    /** 取某个会话的完整内容，用户点击侧边栏某一条时调用 */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String conversationId) {
        Conversation c = conversationService.get(conversationId);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    /** 重命名会话 */
    @PatchMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> renameConversation(@PathVariable String conversationId,
                                                   @RequestBody RenameRequest request) {
        if (conversationService.get(conversationId) == null) {
            return ResponseEntity.notFound().build();
        }
        conversationService.rename(conversationId, request.title());
        return ResponseEntity.ok().build();
    }

    /**
     * 删除会话
     *
     * 两个地方都要清：
     * 1. 磁盘上的历史记录（conversationService）
     * 2. 内存里的对话记忆（chatMemory）——只删文件不清记忆的话，
     *    模型还记得刚才聊过什么，会出现"删了但还记着"的怪现象
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
        chatMemory.clear(conversationId);
        return ResponseEntity.ok().build();
    }
}
