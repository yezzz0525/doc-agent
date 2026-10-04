package com.example.docagent.dto;

/**
 * 文档加载结果
 *
 * @param success    是否成功
 * @param message    给用户看的说明文字
 * @param fileCount  本次加载了几个文件
 * @param chunkCount 切分后共多少个片段入库
 */
public record LoadResult(boolean success, String message, int fileCount, int chunkCount) {
}
