package com.example.docagent.dto;

/**
 * 会话摘要 —— 左侧边栏列表用的轻量对象
 *
 * 为什么不直接返回 Conversation？
 * 因为侧边栏只需要标题和时间，把每个会话的完整消息都传过去纯属浪费
 * （聊得多了，一个会话可能有几十条消息）。
 * 等用户真的点进某个会话，再按 id 去取完整内容。
 *
 * 这叫"按需加载"，是前端列表类界面的标准做法。
 *
 * @param id           会话 ID
 * @param title        会话标题
 * @param updatedAt    最后更新时间
 * @param messageCount 消息条数，显示在标题下方
 */
public record ConversationSummary(String id, String title, String updatedAt, int messageCount) {
}
