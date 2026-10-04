package com.example.docagent.dto;

/**
 * 重命名会话的请求体
 *
 * @param title 新标题
 */
public record RenameRequest(String title) {
}
