package com.example.docagent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：把技术异常翻译成前端能直接展示的中文提示
 *
 * <h3>为什么需要它</h3>
 * 默认情况下 Spring Boot 遇到未捕获异常只会返回
 * <pre>{"status":500,"error":"Internal Server Error","path":"/api/chat"}</pre>
 * —— 前端只能显示"HTTP 500"，用户完全不知道发生了什么。
 * 本项目接的是两家免费模型服务，最常遇到的就是限速和 Key 失效，
 * 这两类必须给出"该怎么办"的指引，否则用户只会以为程序坏了。
 *
 * <h3>安全考虑</h3>
 * 只回传异常消息，<b>不返回堆栈</b>。堆栈留在服务端日志里（log.error），
 * 避免把包名、类名、服务器路径暴露给浏览器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e) {
        String raw = rootMessage(e);
        String lower = raw.toLowerCase();
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message;

        // ① 免费模型限速：向量模型和对话模型都有限速上限，前几次已自动重试过
        if (isRateLimited(lower)) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            message = "请求太密集：模型服务有速率上限（免费模型尤其明显）。"
                    + "**等 10~20 秒后再试一次即可**，已建好的索引不受影响。"
                    + "若频繁出现，可把 application.yaml 里的 doc-agent.embedding.qps 调小（比如 2）。";
        }
        // ② API Key 无效 / 余额不足 —— 【按服务商区分】，
        //    因为本项目同时用两家（百炼对话 + 硅基流动向量），
        //    而闲聊分支不检索文档、不碰硅基流动，所以会出现
        //    「闲聊能答、技术问题报 401」的现象，必须指明是哪一个 Key。
        else if (lower.contains("401") || lower.contains("unauthorized")
                || lower.contains("token is invalid") || lower.contains("令牌")
                || lower.contains("余额不足") || lower.contains("insufficient")) {

            boolean siliconflow = lower.contains("30014")
                    || (lower.contains("token is invalid") && !lower.contains("dashscope"));

            if (siliconflow) {
                message = "**硅基流动的 Key 有问题**（SILICONFLOW_API_KEY）——"
                        + "这一步是「向量检索」，只有问技术问题才会用到，所以闲聊能答、技术问题会失败。"
                        + "请检查：Key 是否复制完整、改完环境变量后有没有重新 Run。"
                        + "快速验证：python tools\\check_config.py";
            } else if (lower.contains("dashscope") || lower.contains("百炼")
                    || lower.contains("qwen")) {
                message = "**百炼的 Key 有问题**（DASHSCOPE_API_KEY）——"
                        + "这一步是对话生成，所有问题都会用到。"
                        + "如果同时提示欠费，去百炼控制台费用中心结清并开启「免费额度用完即停」。"
                        + "快速验证：python tools\\check_config.py";
            } else {
                message = "API Key 无效或已欠费。本项目用两个 Key："
                        + "DASHSCOPE_API_KEY（百炼对话）+ SILICONFLOW_API_KEY（硅基流动向量）。"
                        + "快速跑一次 python tools\\check_config.py 能直接告诉你是哪个出了问题。";
            }
        }
        // ③ 数据库连不上（Docker 没开是高频原因）
        else if (lower.contains("connection refused") || lower.contains("could not connect")
                || lower.contains("failed to configure a datasource")
                || lower.contains("communications link failure")) {
            message = "连不上 PostgreSQL。请先双击桌面「Docker Desktop」等右下角鲸鱼图标变绿，"
                    + "再执行 docker compose up -d，然后重启本应用。";
        }
        // ④ 向量表不存在（切了向量库但没重建索引）
        else if (lower.contains("relation") && lower.contains("does not exist")) {
            message = "向量表不存在。请点页面上的「加载文档」重建索引。";
        }
        // ⑤ 维度不匹配（换了 embedding 模型但没重建索引）
        else if (lower.contains("dimension") && (lower.contains("mismatch") || lower.contains("expected"))) {
            message = "向量维度不匹配：换了向量模型后必须重建索引，"
                    + "请点「加载文档」重新灌一遍文档。";
        }
        // ⑥ 其他：原样把消息带出去，至少比 "HTTP 500" 有用
        else {
            message = raw;
        }

        log.error("请求处理失败（对外返回 {}）", status.value(), e);
        return ResponseEntity.status(status).body(body(status, message));
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", false);
        map.put("code", status.value());
        map.put("message", message);
        return map;
    }

    private boolean isRateLimited(String lower) {
        return lower.contains("429") || lower.contains("ratelimit") || lower.contains("rate limit")
                || lower.contains("速率限制") || lower.contains("限速")
                || lower.contains("请求过于频繁") || lower.contains("too many requests");
    }

    /** 取最内层异常消息：包装层的 "java.lang.Exception: ..." 往往没有信息量 */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}
