package com.example.docagent.dto;

import java.util.List;

/**
 * 一条被持久化下来的对话消息
 *
 * 和 Spring AI 的 Message 有什么区别？
 * - Message 是"给模型看的"，格式由框架决定，存不进文件
 * - StoredMessage 是"给人看的"，就是一个普通对象，能直接序列化成 JSON 存磁盘
 *
 * 两者互相转换：聊天时用 Message，存档/回显时用 StoredMessage。
 *
 * @param role    角色：user（用户）或 assistant（助手）
 * @param text    消息正文（Markdown 源码，前端负责渲染）
 * @param sources 助手消息专属：本次回答引用了哪些文档片段
 * @param mode    助手消息专属：rag（查了文档）/ chat（普通对话）
 * @param time    发生时间，格式 2026-10-04T11:30:00
 */
public record StoredMessage(String role, String text, List<SourceRef> sources, String mode, String time) {

    /**
     * 紧凑构造器：把 null 统一归一化
     *
     * 这一步看起来啰嗦，但它解决一个真实的坑：磁盘上早先存下的 JSON 里没有 mode 字段，
     * Jackson 反序列化时会给它 null，前端拿到 null 就要到处判空。
     * 在这里一次性兜住，上层代码永远拿到合法值。
     */
    public StoredMessage {
        if (role == null || role.isBlank()) {
            role = "assistant";
        }
        if (sources == null) {
            sources = List.of();
        }
        if (mode == null || mode.isBlank()) {
            // 旧数据没有 mode，而它们全是走 RAG 产生的，所以默认成 rag
            mode = ChatResponse.MODE_RAG;
        }
    }

    /** 便捷构造：不带引用来源的消息（用户消息都用这个） */
    public static StoredMessage user(String text, String time) {
        return new StoredMessage("user", text, List.of(), null, time);
    }

    /** 便捷构造：走 RAG 的助手消息 */
    public static StoredMessage assistant(String text, List<SourceRef> sources, String time) {
        return new StoredMessage("assistant", text, sources, ChatResponse.MODE_RAG, time);
    }

    /** 便捷构造：指定模式的助手消息（普通对话模式用这个） */
    public static StoredMessage assistant(String text, List<SourceRef> sources, String mode, String time) {
        return new StoredMessage("assistant", text, sources, mode, time);
    }
}
