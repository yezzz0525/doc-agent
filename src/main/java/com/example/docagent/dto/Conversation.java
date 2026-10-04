package com.example.docagent.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个完整会话（一轮"新对话"到结束之间的所有消息）
 *
 * 它会被序列化成 JSON 存到 data/conversations.json，所以是纯数据、不带任何逻辑。
 *
 * @param id        会话 ID（UUID）
 * @param title     会话标题。默认"新对话"，用户发出第一条消息后自动改成那句话
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间（列表按它倒序排，最近聊的排最上面）
 * @param messages  这个会话里的全部消息，按时间正序
 */
public record Conversation(String id, String title, String createdAt, String updatedAt,
                           List<StoredMessage> messages) {

    /**
     * 紧凑构造器：兜底 null，避免前端拿到 null 报错
     */
    public Conversation {
        if (messages == null) {
            messages = new ArrayList<>();
        }
    }
}
