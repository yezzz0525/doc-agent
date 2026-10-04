package com.example.docagent.dto;

/**
 * 前端发来的提问请求
 *
 * @param conversationId 会话 ID。同一个 ID 的多轮问答共享上下文记忆。
 *                       第一次提问可以不传（或传空），后端会生成一个并返回给前端，
 *                       前端之后每次提问都带上它，就能实现"接着上文聊"。
 * @param question       用户的问题
 */
public record ChatRequest(String conversationId, String question) {
}
