package com.example.docagent.controller;

import com.example.docagent.dto.ChatResponse;
import com.example.docagent.dto.SourceRef;
import com.example.docagent.service.DocumentService;
import com.example.docagent.service.RagChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 调试用接口（返回纯文本，方便直接在浏览器地址栏调）
 *
 * 使用顺序：
 * 1. 先访问 /doc/load 加载文档（第一次建议带 ?maxFiles=10 试水）
 * 2. 再访问 /doc/ask?question=你的问题 基于文档问答
 *
 * 想验证多轮对话，把上一次返回的会话 ID 带上：
 *   /doc/ask?question=那证书怎么续期&conversationId=xxxxxxxx
 *
 * 正式的前端请用 /api/** 那套 JSON 接口（见 ApiController）。
 */
@RestController
public class DocController {

    private final DocumentService documentService;
    private final RagChatService ragChatService;

    public DocController(DocumentService documentService, RagChatService ragChatService) {
        this.documentService = documentService;
        this.ragChatService = ragChatService;
    }

    /**
     * 加载文档到向量库
     * 例：http://localhost:8081/doc/load?maxFiles=10
     */
    @GetMapping(value = "/doc/load", produces = "text/plain;charset=UTF-8")
    public String load(@RequestParam(defaultValue = "tools/spring-docs") String dir,
                       @RequestParam(defaultValue = "999") int maxFiles) throws IOException {
        return documentService.loadDocs(dir, maxFiles).message();
    }

    /**
     * 基于文档问答
     * 例：http://localhost:8081/doc/ask?question=怎么使用Profile区分环境
     */
    @GetMapping(value = "/doc/ask", produces = "text/plain;charset=UTF-8")
    public String ask(@RequestParam String question,
                      @RequestParam(required = false) String conversationId) {
        ChatResponse response = ragChatService.ask(conversationId, question);

        StringBuilder sb = new StringBuilder(response.answer());
        if (!response.sources().isEmpty()) {
            sb.append("\n\n—————— 引用来源 ——————\n");
            for (SourceRef source : response.sources()) {
                sb.append("· ").append(source.file());
                if (source.score() != null) {
                    sb.append("（相似度 %.3f）".formatted(source.score()));
                }
                sb.append('\n');
            }
            sb.append("\n会话 ID：").append(response.conversationId())
              .append("\n（把这个 ID 带进下一次请求的 conversationId 参数，就能继续追问）");
        }
        return sb.toString();
    }
}
