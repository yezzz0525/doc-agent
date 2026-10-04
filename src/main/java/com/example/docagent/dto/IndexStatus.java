package com.example.docagent.dto;

/**
 * 知识库状态，前端启动时调一次，用来显示"现在有多少内容可问"
 *
 * @param ready        索引是否已就绪（片段数 &gt; 0）
 * @param chunkCount   当前向量库里有多少个片段
 * @param indexFile    索引文件路径
 * @param persistable  索引是否已落盘（落盘后重启不用重新 embedding）
 */
public record IndexStatus(boolean ready, int chunkCount, String indexFile, boolean persistable) {
}
