package com.example.docagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 意图路由器 —— 决定一个问题走"查文档"还是"随便聊"
 *
 * 为什么需要它？
 * 没有路由时，所有问题都会被无脑丢进检索环节：
 * 你问"你是谁"，它会硬凑 4 段 Spring 文档塞给模型，然后模型只能说"文档里没有相关内容"。
 * 这不算 bug，是"防幻觉"的代价 —— 但体验很怪。
 *
 * 加上路由之后：
 *   技术问题   → RAG：检索文档 → 看着资料回答 → 带引用来源
 *   闲聊/元问题 → 普通对话：不检索，直接调通义千问回答
 *
 * 这是 Agent 里非常典型的设计，专业叫法是 Intent Routing（意图路由）或 Query Routing。
 * 再往前走一步，就是"按意图选工具"的 Tool Use —— 同一个套路，只是出口从两种变成 N 种。
 */
@Service
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** 分类标签 */
    public enum Intent {
        /** 技术问题：需要查文档 */
        TECH,
        /** 闲聊或关于助手自身的问题：直接问模型，不查文档 */
        CHAT
    }

    /**
     * 寒暄白名单：整句命中就直接判为闲聊，省掉一次模型调用
     *
     * 注意这里用的是"整句相等"，而不是"包含"。
     * 如果用包含，"你好，Spring 怎么配 SSL" 会被误判成闲聊 ——
     * 一个字的差别就是 bug，这类判断一定要卡死边界。
     */
    private static final Set<String> CHITCHAT = Set.of(
            "你好", "您好", "hi", "hello", "嗨", "在吗", "在么", "在不在",
            "谢谢", "感谢", "多谢", "辛苦啦", "thanks", "thank you", "3q",
            "你是谁", "你叫什么", "你叫什么名字", "介绍一下你自己", "自我介绍",
            "你能做什么", "你会什么", "你能干什么", "你有什么功能", "你能帮我做什么",
            "再见", "拜拜", "晚安", "bye");

    /**
     * 技术关键词表 —— 命中就直接判 TECH，连模型分类都不用调
     *
     * <h3>为什么要加这一层</h3>
     * 原来只有"寒暄白名单"能免掉模型调用，其余问题一律要走一次 chat 请求去问模型"这是技术问题吗"。
     * 代价是实打实的：
     * <ul>
     *   <li><b>慢</b>：每次提问前凭空多出 1~2 秒（用户看到的就是"转圈很久才出第一个字"）</li>
     *   <li><b>限速风险翻倍</b>：对话模型被调用的次数从 1 次变成 2 次。
     *       之前实测的 429 就是这么来的 —— 聊天模型免费不等于不限速</li>
     * </ul>
     * 加上这层之后，日常技术问题（如"怎么自定义一个 starter"）<b>零额外调用</b>，
     * 只有"既不像寒暄、又没有技术关键词"的模糊问题才花钱去问模型。
     *
     * <h3>误判的代价可以接受</h3>
     * 关键词命中但其实是闲聊（比如"帮我写首关于 Spring 的诗"）会走 RAG，
     * 结果是模型说"文档资料中没有找到相关内容"。这个代价只是回答不理想，
     * 而反过来把技术问题误判成闲聊（不查文档、模型自由发挥）才是真正危险的。
     * 路由设计的默认方向应该是<b>宁可多查一次，不可乱答</b>。
     *
     * <h3>行业里这叫什么</h3>
     * "规则优先 + 模型兜底"的分流策略。零成本、可预测、还能省 API 调���，
     * 是意图路由在生产环境里的标准第一层；模型分类只用来处理规则覆盖不到的模糊地带。
     */
    private static final Set<String> TECH_HINTS = Set.of(
            // 框架 / 语言 / 工具
            "spring", "boot", "java", "jvm", "maven", "gradle", "mybatis", "jpa", "hibernate",
            "redis", "docker", "mysql", "postgres", "database", "sql", "git", "linux", "nginx",
            "python", "golang", "rust", "typescript", "javascript", "node", "vue", "react", "angular",
            "kotlin", "scala", "c++", "php", "ruby", "shell", "脚本",
            // Spring 生态专有名词
            "starter", "bean", "aop", "ioc", "controller", "service", "repository",
            "mapper", "entity", "filter", "interceptor", "config", "configuration",
            "注解", "配置", "依赖", "注入", "过滤器", "拦截器", "事务", "自动装配",
            // 架构与设计模式（补这一批是因为 "MVVM 是什么" 这类问法一个常见词都命中不了）
            "mvvm", "mvc", "ddd", "微服务", "分布式", "单例", "工厂模式", "策略模式",
            "观察者", "设计模式", "架构", "算法", "数据结构", "编译", "反编译",
            // 通用技术概念
            "orm", "dto", "vo", "rpc", "grpc", "http", "rest", "api", "jdbc", "test",
            "jwt", "oauth", "cors", "k8s", "kubernetes", "消息队列", "kafka", "rabbitmq",
            "线程池", "锁", "缓存", "索引", "消息", "协议", "端口", "内存", "性能", "安全",
            "部署", "启动", "打包", "接口", "异常", "线程", "并发", "鉴权", "跨域", "断点",
            "pom", "yml", "yaml", "json", "xml", "websocket", "devtools", "actuator",
            "开发工具", "虚拟机", "服务器", "数据库", "字段", "表结构", "返回值", "参数");

    /**
     * 分类用的提示词
     *
     * 关键字写得很克制：只做二选一，并明确要求"只输出标签"。
     * 让模型做分类时，输出越短越稳 —— 一旦要求它解释理由，它就容易多说话。
     */
    private static final String CLASSIFY_PROMPT = """
            你是一个意图分类器。判断用户的问题属于下面哪一类，只输出一个标签，不要有任何其他文字。

            TECH —— 与 Spring / Java / 编程 / 框架配置 / 软件使用相关的技术问题。
            CHAT —— 其他一切：打招呼、闲聊、问你是谁、问你能做什么、以及与技术无关的问题。

            只输出 TECH 或 CHAT。
            """;

    private final ChatClient routerClient;

    public IntentRouter(ChatClient.Builder builder) {
        // 这个 ChatClient 专门用来分类，绝不挂记忆 Advisor：
        // 分类是"无状态"的判断，带上历史只会浪费 token 并干扰结果
        this.routerClient = builder.build();
    }

    /**
     * 意图分类的总开关
     *
     * <p><b>默认关闭（纯规则）</b>，这是被逼出来的决定，原因很具体：
     * 模型分类本身要调一次对话模型，而部分免费档<b>只允许 1 并发</b>。
     * 一次问答本来就要调它生成回答，分类那一次纯属额外开销，还会撞 429
     * —— 用户问"我想吃美食有什么推荐"时就是这么卡住的。
     *
     * <p>规则层的判断力其实够用：寒暄白名单 + 50 个技术关键词 + 上下文追问检测，
     * 能覆盖日常 95% 的情况，而且<b>零延迟、零成本、行为可预测</b>。
     *
     * <p><b>什么时候该打开</b>：等你换成不限并发的服务商（比如百度千帆 QPS=50），
     * 或者线上遇到规则没覆盖住的问法时。打开后不确定的情况会问模型，
     * 拿到的答案会打 info 日志，方便你回头补关键词。
     */
    @Value("${doc-agent.intent.use-model-classify:false}")
    private boolean useModelClassifier;

    /**
     * 判断意图
     *
     * @param question 用户当前的问题
     * @param history  最近对话。用于识别追问——"那证书呢"单看这句不像技术问题，
     *                 但上一轮在聊 SSL，就该按技术问题处理
     */
    public Intent classify(String question, List<Message> history) {
        String q = question == null ? "" : question.strip();

        // 第 1 层：整句就是一句寒暄 → CHAT（零调用）
        if (CHITCHAT.contains(q.toLowerCase())) {
            log.info("意图判定[白名单] {} → CHAT（未调用模型）", q);
            return Intent.CHAT;
        }

        // 第 2 层：命中技术关键词 → TECH（零调用，救时间的最大功臣）
        String matched = matchTechHint(q);
        if (matched != null) {
            log.info("意图判定[关键词「{}」] {} → TECH（未调用模型）", matched, q);
            return Intent.TECH;
        }

        // 最近对话压成一小段上下文，第 3 层和第 4 层都要用
        String context = recentContext(history);

        // 第 3 层：技术会话里的追问 → TECH。
        // 关键词表不可能覆盖所有技术词（"MVVM 是什么"一个都命中不了），
        // 但只要上文在聊技术，"那证书呢""怎么配置"这种省略句就该按技术处理。
        //
        // ⚠️ 这里踩过一次坑：第一版写成"上文本轮出现技术词就判 TECH"，
        // 结果用户先问了 Spring 的问题，紧接着问"有什么好吃的美食"，
        // 因为上文有 boot 就被判成技术问题 → 硬去查 Spring 文档 → 答非所问。
        //
        // 修正的关键是判断"**这句话自己能不能问得完整**"：
        //   "有什么好吃的美食" 含"什么"，是完整问句，不该依赖上下文；
        //   "那证书呢"       不含任何疑问词，是省略句，必须看上文。
        // 完整问句一律走第 5 层兜底，不受历史影响。
        if (isEllipsis(q)) {
            String contextHint = matchTechHint(context);
            if (contextHint != null) {
                log.info("意图判定[省略句+上文关键词「{}」] {} → TECH（未调用模型）", contextHint, q);
                return Intent.TECH;
            }
        }

        // 第 4 层：规则完全没命中，且显式开启了模型分类 → 才花钱问模型
        if (useModelClassifier) {
            return classifyByModel(q, context);
        }

        // 第 5 层：兜底 → CHAT。
        // 理由：问题里一个技术词都没有、上文也没有技术信号，那它大概率就是个普通问题。
        // 让模型用自己的知识直接答，比硬塞 4 段 Spring 文档进去更合适。
        log.info("意图判定[兜底] {} → CHAT（未调用模型）", q);
        return Intent.CHAT;
    }

    /** 第 4 层：交给模型分类 */
    private Intent classifyByModel(String q, String context) {
        long start = System.nanoTime();
        try {
            String raw = routerClient.prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(context.isEmpty()
                            ? q
                            : "【上文】" + context + "\n【当前问题】" + q)
                    .call()
                    .content();

            Intent intent = parse(raw);
            log.info("意图判定[模型分类，耗时 {} ms] {} → {}",
                    (System.nanoTime() - start) / 1_000_000, q, intent);
            return intent;
        } catch (Exception e) {
            // 分类失败时降级走 CHAT（而不是 TECH）。
            // 为什么？走到这里说明规则也没命中，那这个问题八成不是技术问题——
            // 让它去查 Spring 文档只会得到"文档里没有"，那是更差的结果。
            log.warn("意图判定调用模型失败，本次按普通对话处理。原因：{}", e.getMessage());
            return Intent.CHAT;
        }
    }

    /** 返回第一个命中的技术关键词，没命中返回 null */
    private static String matchTechHint(String q) {
        String lower = q.toLowerCase();
        for (String hint : TECH_HINTS) {
            if (lower.contains(hint)) {
                return hint;
            }
        }
        return null;
    }

    /**
     * 判断一句话是不是「省略句」—— 也就是必须依赖上文才能 understand 的那种
     *
     * <p>判据有两条，缺一不可：
     * <ol>
     *   <li><b>够短</b>：超过 10 个字通常是个完整问题，不必翻历史</li>
     *   <li><b>不含任何疑问焦点词</b>：中文里"什么/怎么/为什么"这类词一出现，
     *       就说明用户自己已经把问题说全了，不需要上下文补</li>
     * </ol>
     *
     * 对照：
     * <pre>
     *   "有什么好吃的美食"  含"什么" → 完整问句 → 不看历史
     *   "那证书呢"            无疑问词且很短 → 省略句 → 要看历史
     *   "怎么配置 SSL 证书"    含"怎么" → 完整问句
     * </pre>
     */
    private static boolean isEllipsis(String q) {
        if (q == null || q.length() > 10) {
            return false;
        }
        for (String marker : SELF_CONTAINED_MARKERS) {
            if (q.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 疑问焦点词表：出现这些词就认为用户已经把问题说完整了，不需要看上下文
     *
     * 收录了口语里的"怎么弄""咋办""咋整"这类说法 —— 它们在技术场景里太常见了，
     * 漏掉会导致"这个怎么弄"被当成省略句去翻历史。
     */
    private static final Set<String> SELF_CONTAINED_MARKERS = Set.of(
            "什么", "怎么", "如何", "为什", "为啥", "哪里", "哪些", "哪个", "谁", "几点",
            "多少", "是不是", "能否", "能不能", "可不可以", "可以吗", "咋办", "咋整", "怎么弄");

    private static Intent parse(String raw) {
        if (raw == null) {
            return Intent.TECH;
        }
        String s = raw.strip().toUpperCase();
        // 模型偶尔会多说几个字（"答案是 TECH"），所以用包含判断兜底
        if (s.contains("CHAT")) {
            return Intent.CHAT;
        }
        if (s.contains("TECH")) {
            return Intent.TECH;
        }
        return Intent.TECH;
    }

    /** 把最近几条对话压成一小段上下文，供分类时参考 */
    private static String recentContext(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        // 只取最后 4 条（约两轮），既够理解追问，又不至于让分类提示词变长
        int from = Math.max(0, history.size() - 4);
        for (int i = from; i < history.size(); i++) {
            Message m = history.get(i);
            String text;
            String who;
            if (m instanceof UserMessage u) {
                text = u.getText();
                who = "用户";
            } else if (m instanceof AssistantMessage a) {
                text = a.getText();
                who = "助手";
            } else {
                continue;
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > 120) {
                text = text.substring(0, 120) + "…";
            }
            sb.append(who).append("：").append(text).append('\n');
        }
        return sb.toString().strip();
    }
}
