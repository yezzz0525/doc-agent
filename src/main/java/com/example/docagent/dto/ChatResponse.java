package com.example.docagent.dto;

import java.util.List;

/**
 * 问答接口的返回值
 *
 * @param conversationId 会话 ID。首次提问时后端生成，前端要存下来，后续提问带回
 * @param answer         模型生成的回答
 * @param sources        本次回答引用的文档片段列表（闲聊模式下为空）
 * @param mode           本次回答走的是哪条路：rag（查了文档）/ chat（普通对话）
 */
public record ChatResponse(String conversationId, String answer, List<SourceRef> sources, String mode) {

    /** 走了 RAG：检索了文档，回答有依据、带引用 */
    public static final String MODE_RAG = "rag";

    /** 走了普通对话：没查文档，直接问的模型 */
    public static final String MODE_CHAT = "chat";

    public static ChatResponse rag(String conversationId, String answer, List<SourceRef> sources) {
        return new ChatResponse(conversationId, answer,
                sources == null ? List.of() : sources, MODE_RAG);
    }

    public static ChatResponse chat(String conversationId, String answer) {
        return new ChatResponse(conversationId, answer, List.of(), MODE_CHAT);
    }

    /**
     * 走的是普通对话分支，但<b>模型自己调用了知识库工具</b>，所以带上了引用来源
     *
     * <p>这就是 Tool Use 模式和纯路由模式的区别：路由模式下 mode 只有 rag/chat 二分，
     * 而"模型自主决定查不查"之后，chat 分支也可能带引用 —— 前端的标签逻辑要相应放宽。
     */
    public static ChatResponse chatWithSources(String conversationId, String answer, List<SourceRef> sources) {
        return new ChatResponse(conversationId, answer,
                sources == null ? List.of() : sources, MODE_CHAT);
    }
}
