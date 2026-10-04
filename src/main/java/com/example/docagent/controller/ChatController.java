package com.example.docagent.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第一个 AI 测试接口
 * 访问方式：浏览器打开 http://localhost:8081/ai/chat
 * 也可以带参数：http://localhost:8081/ai/chat?message=什么是依赖注入
 */
@RestController
public class ChatController {

    // Spring AI 的统一对话入口，后面所有的问答、Agent 编排都用它
    private final ChatClient chatClient;

    // 框架启动时会自动根据 application.yaml 的配置创建好 Builder，注入进来直接用
    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping(value = "/ai/chat", produces = "text/plain;charset=UTF-8")
    public String chat(@RequestParam(defaultValue = "用一句话介绍你自己") String message) {
        return chatClient.prompt()
                .user(message)   // 用户的问题
                .call()          // 发起调用（同步等待，适合测试；以后会换成流式）
                .content();      // 取出回答的纯文本
    }
}
