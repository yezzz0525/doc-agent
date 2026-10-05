package com.example.docagent.config;

import com.example.docagent.service.TokenUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;

/**
 * RAG 基础配置：注册两个核心组件
 *
 * 1. VectorStore —— 向量仓库，存文档片段（RAG 的"存"这一半）
 * 2. ChatMemory  —— 对话记忆，存历史消息（多轮对话的本质）
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /** 索引文件路径，可在 application.yaml 的 doc-agent.index.path 里修改 */
    private final String indexPath;

    /** pgvector 模式下的表名 */
    private final String vectorTable;

    /** pgvector 模式下的向量维度，必须和 embedding 模型的输出维度一致（bge-m3 = 1024） */
    private final int dimensions;

    /** 限速时的重试次数（0 表示不重试） */
    private final int embedRetries;

    /** 重试基础等待毫秒数，实际等待 = 基础值 × 2^(次数-1) + 随机抖动 */
    private final long embedRetryDelayMillis;

    public RagConfig(@Value("${doc-agent.index.path:data/vector-index.json}") String indexPath,
                     @Value("${doc-agent.vector-store.table:vector_store}") String vectorTable,
                     @Value("${doc-agent.vector-store.dimensions:1024}") int dimensions,
                     @Value("${doc-agent.embedding.max-retries:3}") int embedRetries,
                     @Value("${doc-agent.embedding.retry-delay-ms:1000}") long embedRetryDelayMillis) {
        this.indexPath = indexPath;
        this.vectorTable = vectorTable;
        this.dimensions = dimensions;
        this.embedRetries = embedRetries;
        this.embedRetryDelayMillis = embedRetryDelayMillis;
    }

    /**
     * 给对话模型套上「单并发守卫 + token 统计」
     *
     * <b>为什么必须加</b>：部分免费档服务商（如智谱 GLM）<b>只允许 1 个并发请求</b>，这是服务商侧限制。
     * 一次问答要调它一到两次（意图分类 + 回答生成），上一条流式回答没结束就发新问题，
     * 必然撞 429。用信号量在客户端串行化，从源头避免被拒。
     *
     * <p>时机上没问题：ChatClient 依赖 ChatModel 注入，Spring 会先创建原始 bean、
     * 应用本处理器、拿到包装后的实例再注入。
     */
    @Bean
    public static BeanPostProcessor chatGuardPostProcessor(TokenUsageService usageService) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                // ChatModel 本身继承了 StreamingChatModel，所以包装后两者都还在
                if (bean instanceof ChatModel model && !(model instanceof GuardedChatModel)) {
                    log.info("已为对话模型 {} 启用单并发守卫（免费模型仅允许 1 并发，防止撞 429）",
                            model.getClass().getSimpleName());
                    return new GuardedChatModel(model, usageService, 120);
                }
                return bean;
            }
        };
    }

    /**
     * 给所有 EmbeddingModel 套上「限速自动重试」
     *
     * 为什么用 BeanPostProcessor 而不是直接 @Bean 声明？
     * 因为 EmbeddingModel 这个 bean 是 Spring AI 自动配置生成的（OpenAiApi + 模型名），
     * 我们不能重新 new 一个替代它，只能在它初始化完成后<b>包一层</b>再放回去。
     *
     * 时序上没问题：其他 bean（比如 PgVectorStore）注入 EmbeddingModel 时，
     * Spring 会先创建原始 bean、应用本处理器、拿到包装后的实例再注入。
     *
     * 只包 EmbeddingModel，不包 ChatModel ——
     * 免费档模型真限速的概率极低，而且流式回答被重试会导致重复输出。
     */
    @Bean
    public static BeanPostProcessor embeddingRetryPostProcessor(
            @Value("${doc-agent.embedding.max-retries:3}") int maxRetries,
            @Value("${doc-agent.embedding.retry-delay-ms:1000}") long delayMillis,
            @Value("${doc-agent.embedding.qps:4}") double qps) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof EmbeddingModel model && !(model instanceof RetryEmbeddingModel)) {
                    log.info("已为向量模型 {} 启用限速保护（客户端限速 {} QPS，撞限速后重试 {} 次，基础等待 {} ms）",
                            model.getClass().getSimpleName(), qps, maxRetries, delayMillis);
                    return new RetryEmbeddingModel(model, maxRetries, delayMillis, qps);
                }
                return bean;
            }
        };
    }

    /**
     * 向量库之一：内存版（默认方案）
     *
     * SimpleVectorStore = 内存版向量库，数据存在 JVM 里，读写快、零配置。
     * 唯一的问题是"重启就丢"，所以这里用 save/load 把它落盘：
     *   - 启动时：本地有索引文件就直接读回来（省掉重新 embedding 的时间和费用）
     *   - 加载文档后：DocumentService 会调 save() 把索引写回磁盘
     *
     * 为什么返回类型写 SimpleVectorStore 而不是接口 VectorStore？
     * 因为 save()/load() 是 SimpleVectorStore 特有的方法，接口里没有。
     * 业务代码（RagChatService）依旧按 VectorStore 接口注入——
     * 将来换成 pgvector，只要改这里一个地方，业务代码一行都不用动。
     *
     * matchIfMissing = true：没配 doc-agent.vector-store.type 时就用它
     */
    @Bean
    @ConditionalOnProperty(name = "doc-agent.vector-store.type", havingValue = "simple", matchIfMissing = true)
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        // 把 EmbeddingModel 传进去：每次 add() 文档时，它会自动把文本转成向量
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        File indexFile = new File(indexPath);
        if (indexFile.exists() && indexFile.length() > 0) {
            try {
                store.load(indexFile);
                log.info("已从本地恢复向量索引：{}（本次启动无需重新 embedding）", indexFile.getAbsolutePath());
            } catch (Exception e) {
                log.warn("向量索引加载失败，将以空索引启动，重新调用 /api/load 即可重建。原因：{}", e.getMessage());
            }
        } else {
            log.info("尚未生成向量索引，请调用 /api/load 加载文档（完成后会自动落盘到 {}）", indexPath);
        }
        return store;
    }

    /**
     * 向量库之二：PostgreSQL + pgvector（把向量存进真正的数据库）
     *
     * 把 application.yaml 里的 doc-agent.vector-store.type 改成 pgvector 就切到它。
     *
     * 和内存版的差别：
     *   内存版 —— 数据在 JVM 里，靠 save/load 落盘成 JSON，重启要读回来；
     *            数据量一大（几万片段）内存和启动时间都吃不消，也没法多个实例共享
     *   pgvector —— 数据本来就在数据库里，天然持久化、可共享、能建索引加速检索；
     *             代价是要装 PostgreSQL 并启用 pgvector 扩展（用 docker-compose 一行搞定）
     *
     * 几个参数的含义：
     *   dimensions   —— 向量维度，必须和 embedding 模型一致。bge-m3 是 1024，
     *                   写错会导致入库时维度不匹配报错（这也是"换模型要重建索引"的原因）
     *   distanceType —— 距离度量，COSINE_DISTANCE（余弦距离）是文本相似度检索的常规选择
     *   indexType    —— HNSW 是 pgvector 里最常用的近似索引，检索快、召回率也高
     *   initializeSchema —— true 时自动执行 create extension 和建表，省得手动跑 SQL
     *
     * idType —— 这里有个必须显式指定的坑（实测踩过）
     *
     * PgVectorStore 默认用 UUID 作主键，可我们的片段 ID 是 DocumentService 里
     * 生成的"文件名#序号"（如 001-security-saml2.md#0）——这是为了让重复加载文档时
     * 变成"覆盖同一条"而不是"又插一遍"。UUID 主键直接把这个字符串拒绝了：
     *
     *     java.lang.IllegalArgumentException: Invalid UUID string: 001-security-saml2.md#0
     *
     * 两种解法都试过：
     *   ① 把文件名哈希成 UUID（UUID.nameUUIDFromBytes）—— 库能写，但表里全是
     *      十六进制，肉眼看不出这条向量属于哪个文件，调试和演示都吃亏
     *   ② 主键改成 TEXT —— 稳定 ID 原样保留，可读、可查、便于核对，还顺手解决了 ① 的问题
     * 选了 ②，代价只是主键字符串比 uuid 长一点，对 HNSW 索引毫无影响（索引建在 embedding 列上）。
     *
     * ⚠️ 改主键类型后必须删表重建（表里现在是 0 行，删了没成本）：
     *     docker exec -it doc-agent-pg psql -U docagent -d docagent -c "\d vector_store"
     *     看到 id 列是 uuid 就说明还是旧表
     */
    @Bean
    @ConditionalOnProperty(name = "doc-agent.vector-store.type", havingValue = "pgvector")
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        log.info("向量库使用 PostgreSQL + pgvector（表 {}，维度 {}，主键 TEXT）", vectorTable, dimensions);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(vectorTable)
                .dimensions(dimensions)
                // 主键用 TEXT，才能存得下"文件名#序号"这种可读且稳定的 ID
                .idType(PgVectorStore.PgIdType.TEXT)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }

    /**
     * 对话记忆
     *
     * MessageWindowChatMemory = "滑动窗口"记忆：只保留最近 N 条消息。
     * 为什么不无限保留？一是提示词长度有上限，二是越久远的内容越可能是噪音。
     * 20 条大约相当于 10 轮问答，技术问答场景够用。
     *
     * InMemoryChatMemoryRepository = 记忆存内存，重启清空。
     * 以后想让它持久化，换成 JdbcChatMemoryRepository 即可（Spring AI 自带实现）。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
