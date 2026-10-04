package com.example.docagent.dto;

/**
 * token 用量统计
 *
 * @param promptTokens     输入 token（系统提示词 + 检索到的资料 + 对话历史 + 你的问题）
 * @param completionTokens 输出 token（模型生成的回答）
 * @param totalTokens      两者之和
 * @param requestCount     累计请求次数
 * @param estimatedCostCny 按两家免费/低价模型的公开单价估算的人民币成本
 */
public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long requestCount,
        double estimatedCostCny
) {
}
