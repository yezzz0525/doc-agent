package com.example.docagent.service;

import com.example.docagent.dto.ChatResponse;

/**
 * 流式回答的"出口"：模型每蹦出几个字，就通过这个接口往外推一次
 *
 * 为什么要有这么个接口？
 * 让 RagChatService 只管"怎么生成"，不关心"往哪儿发"。
 * 至于往 SSE 发、往 WebSocket 发、还是往控制台打印，那是上层（Controller）的事。
 * 这样分层，service 里就不会出现 web 相关的类，将来换传输方式不用改业务代码。
 *
 * 一次流式回答的事件顺序：
 *   onMeta    → 只有一次，最先到（告诉前端：会话 ID、这次走哪条路、引用来源有哪些）
 *   onSources → 0~1 次，Tool Use 模式下「模型调用知识库工具之后」才可能有值
 *   onToken   → 很多次（每次一小段文字，几个字到几十个字不等）
 *   onComplete / onError → 最后各来一次，收尾
 */
public interface StreamSink {

    /** 第一个事件：回答的"元信息"，此时正文还没开始生成 */
    void onMeta(ChatResponse meta);

    /**
     * 引用来源（可选事件）
     *
     * <p>为什么需要它：Tool Use 模式下，模型是<b>先调用知识库工具、拿到结果之后</b>才开始回答，
     * 所以引用来源在 {@link #onMeta} 那一刻<b>还不存在</b>。这个回调就是那时候补发出去的。
     *
     * <p>前端收到后应该<b>追加</b>而不是整体替换——因为 meta 里可能已经带了一份
     * （RAG 分支的情况），此时是覆盖；也可能 meta 里没有（闲聊分支），此时是首次填充。
     */
    default void onSources(java.util.List<com.example.docagent.dto.SourceRef> sources) {
        // 默认空实现：RAG 分支不需要这个事件，只有 Tool Use 分支才会用到
    }

    /** 正文片段。注意这一小段可能是半个字（中文被拆开的情况由模型端保证不会） */
    void onToken(String text);

    /** 出错了（模型超时、Key 无效等） */
    void onError(String message);

    /** 正常结束 */
    void onComplete();
}
