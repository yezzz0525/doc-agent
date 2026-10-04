package com.example.docagent.dto;

/**
 * 一条引用来源
 *
 * 这是"引用溯源"能力的载体：前端拿到它之后，可以把答案和原始文档对应起来，
 * 用户点一下就能看到"这句话是根据哪篇文档的哪一段说出来的"。
 *
 * @param file    来源文件名（如 004-features-ssl.md），用于追溯原始文件
 * @param title   人类可读的标题（如 "SSL"），前端优先显示它
 * @param score   相似度分数（越大越相关），用于给用户一个"可信度"参考
 * @param snippet 命中的原文片段，让用户可以直接核对
 */
public record SourceRef(String file, String title, Double score, String snippet) {

    /** 片段太长时截断，避免前端面板被一大段文字撑爆 */
    private static final int MAX_SNIPPET = 600;

    /**
     * 构造一条引用来源
     *
     * @param file  文件名
     * @param title 文档标题（可能为 null，前端会退回用文件名美化显示）
     * @param score 相似度
     * @param text  命中的原文
     */
    public static SourceRef of(String file, String title, Double score, String text) {
        String snippet = text == null ? "" : text.strip();
        if (snippet.length() > MAX_SNIPPET) {
            snippet = snippet.substring(0, MAX_SNIPPET) + "……";
        }
        String readable = (title == null || title.isBlank()) ? null : title.strip();
        return new SourceRef(file, readable, score, snippet);
    }
}
