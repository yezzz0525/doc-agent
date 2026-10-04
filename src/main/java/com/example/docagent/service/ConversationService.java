package com.example.docagent.service;

import com.example.docagent.dto.Conversation;
import com.example.docagent.dto.ConversationSummary;
import com.example.docagent.dto.StoredMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话存储服务：让"历史对话"真正留得下来
 *
 * 为什么需要它？
 * Spring AI 的 ChatMemory 只负责"把最近几条消息喂给模型"，它存在内存里，
 * 应用一重启就全没了，也存不了引用来源这类结构化信息。
 * 所以这里另起一套：把完整会话（含时间、引用来源）写成 JSON 文件。
 *
 * 存哪里？data/conversations.json —— 就在项目目录下，可以直接打开看，
 * 也能用文本编辑器改（改前记得停应用）。
 *
 * 为什么不直接上 MySQL？
 * 现在的数据量（几百条消息）用文件完全够，而且零安装、零配置，方便你调代码。
 * 等以后要做多用户、多设备同步，再把这一层换成数据库——业务代码不用改，
 * 因为 Controller 只依赖这里的方法签名。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 时间格式：能被前端 new Date() 正确解析的"无时区"写法 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 标题最多留多少个字，太长就在侧边栏撑破了 */
    private static final int TITLE_MAX = 24;

    private static final String DEFAULT_TITLE = "新对话";

    /**
     * 自己 new 一个 ObjectMapper 而不用注入的：
     * Spring Boot 4 同时带着 Jackson 2 和 Jackson 3 两套，注入容易撞车，
     * 这里只做本地 JSON 读写，独立用一个最省心。
     */
    private final ObjectMapper mapper = new ObjectMapper();

    private final String storePath;

    /** 全部会话，key 是会话 ID。用 LinkedHashMap 保持插入顺序 */
    private final Map<String, Conversation> conversations =
            Collections.synchronizedMap(new LinkedHashMap<>());

    public ConversationService(
            @Value("${doc-agent.conversation.path:data/conversations.json}") String storePath) {
        this.storePath = storePath;
        load();
    }

    // ==================== 读 ====================

    /** 侧边栏列表：最近更新的排最前面 */
    public synchronized List<ConversationSummary> list() {
        return conversations.values().stream()
                .sorted(Comparator.comparing(Conversation::updatedAt, Comparator.reverseOrder()))
                .map(c -> new ConversationSummary(c.id(), c.title(), c.updatedAt(), c.messages().size()))
                .toList();
    }

    /** 取一个会话的完整内容（含全部消息和引用来源） */
    public synchronized Conversation get(String id) {
        return conversations.get(id);
    }

    /** 取一个会话里的消息列表 */
    public synchronized List<StoredMessage> messages(String id) {
        Conversation c = conversations.get(id);
        return c == null ? List.of() : List.copyOf(c.messages());
    }

    public synchronized int count() {
        return conversations.size();
    }

    // ==================== 写 ====================

    /**
     * 确保会话存在。
     *
     * 前端点"新对话"时并不会立刻通知后端（那样会留下一堆空会话），
     * 而是等用户真正问出第一句话，后端在这里顺手把会话建出来。
     * 这叫"懒创建"，能避免列表里堆满空壳。
     */
    public synchronized void ensure(String id) {
        if (conversations.containsKey(id)) {
            return;
        }
        String now = now();
        conversations.put(id, new Conversation(id, DEFAULT_TITLE, now, now, new ArrayList<>()));
        persist();
    }

    /**
     * 往会话里追加一条消息
     *
     * 顺带做一件事：如果标题还是默认的"新对话"，就把第一条用户消息当标题——
     * 这样侧边栏一眼就能看出每个会话聊的是什么。
     */
    public synchronized void append(String id, StoredMessage message) {
        if (!conversations.containsKey(id)) {
            ensure(id);
        }
        Conversation c = conversations.get(id);
        if (c == null) {
            return;
        }

        List<StoredMessage> messages = new ArrayList<>(c.messages());
        messages.add(message);

        String title = c.title();
        boolean isDefaultTitle = title == null || title.isBlank() || DEFAULT_TITLE.equals(title);
        if (isDefaultTitle && "user".equals(message.role())) {
            title = shorten(message.text());
        }

        conversations.put(id, new Conversation(id, title, c.createdAt(), now(), messages));
        persist();
    }

    /** 重命名 */
    public synchronized void rename(String id, String title) {
        Conversation c = conversations.get(id);
        if (c == null) {
            return;
        }
        conversations.put(id, new Conversation(id, shorten(title), c.createdAt(), now(), c.messages()));
        persist();
    }

    /** 删除整个会话 */
    public synchronized void delete(String id) {
        if (conversations.remove(id) != null) {
            persist();
        }
    }

    /** 清空所有会话（提供接口，但界面上没放按钮，避免误点） */
    public synchronized void deleteAll() {
        conversations.clear();
        persist();
    }

    public String storeLocation() {
        return new File(storePath).getAbsolutePath();
    }

    // ==================== 落盘 / 恢复 ====================

    /**
     * 启动时从文件恢复
     *
     * 注意这里的失败处理：读不出来只记警告，绝不删原文件。
     * 万一哪天数据格式变了导致解析失败，你的历史记录还在磁盘上，能人工抢救。
     */
    private void load() {
        File file = new File(storePath);
        if (!file.exists() || file.length() == 0) {
            log.info("暂无历史对话记录，首次提问后会自动创建：{}", file.getAbsolutePath());
            return;
        }
        try {
            List<Conversation> loaded = mapper.readValue(file, new TypeReference<List<Conversation>>() {
            });
            for (Conversation c : loaded) {
                conversations.put(c.id(), c);
            }
            log.info("已恢复 {} 个历史会话：{}", loaded.size(), file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("历史会话文件解析失败，本次以空列表启动（原文件未删除，可人工检查）：{}", e.getMessage());
        }
    }

    /**
     * 全量写回文件
     *
     * 每次变更都整体重写，看起来笨，但对几百条消息的量级完全够用，
     * 而且做到"文件内容永远是完整可读的 JSON"，不会出现半截文件。
     * 真正上量以后再换数据库。
     */
    private void persist() {
        try {
            File file = new File(storePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("会话目录创建失败：{}", parent.getAbsolutePath());
                return;
            }
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file, new ArrayList<>(conversations.values()));
        } catch (Exception e) {
            log.warn("历史会话保存失败（不影响本次问答）：{}", e.getMessage());
        }
    }

    // ==================== 小工具 ====================

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 把一段话压成适合当标题的短句 */
    private static String shorten(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_TITLE;
        }
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX) + "…";
    }
}
