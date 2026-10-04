package com.example.docagent.service;

import com.example.docagent.dto.IndexStatus;
import com.example.docagent.dto.LoadResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 文档服务：负责 RAG 的"存"这一半
 *
 * 流程：读取 .md 文件 → 切分成小片段 → 向量化 → 存入向量库 → 索引落盘
 *
 * 为什么要切分？
 * - 一整页文档太长，塞不进提示词，也不利于精准检索
 * - 切成几百 token 的小段，检索时才能只捞出"最相关的那几段"
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /**
     * 向量库。类型写接口 VectorStore，而不是具体的 SimpleVectorStore ——
     * 这样换成 pgvector 时这个类完全不用改。需要用到内存版特有的 save/load 时，
     * 再用 instanceof 判断一下（见 persistIndex）。
     */
    private final VectorStore vectorStore;

    /**
     * 只有 pgvector 模式才用得上：直接查库统计片段数。
     * 用 ObjectProvider 包一层而不是直接注入，是为了让"没有数据源"的时候也能启动。
     */
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    private final String vectorTable;
    private final String indexPath;

    /** 加载文档时每批送几段去向量化。批越大越快，但也越容易撞服务商限速 */
    private final int batchSize;

    /** 每批之间的停顿毫秒数，给免费模型的速率上限留点余量 */
    private final long batchDelayMillis;

    /** 只用来读索引文件里有多少条，不参与业务序列化（同 ConversationService 的做法） */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前向量库里的片段总数，供 /api/status 查询 */
    private final AtomicInteger chunkCount = new AtomicInteger(0);

    public DocumentService(VectorStore vectorStore,
                           ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                           @Value("${doc-agent.vector-store.table:vector_store}") String vectorTable,
                           @Value("${doc-agent.index.path:data/vector-index.json}") String indexPath,
                           @Value("${doc-agent.embedding.batch-size:4}") int batchSize,
                           @Value("${doc-agent.embedding.batch-delay-ms:150}") long batchDelayMillis) {
        this.vectorStore = vectorStore;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.vectorTable = vectorTable;
        this.indexPath = indexPath;
        this.batchSize = Math.max(1, batchSize);
        this.batchDelayMillis = Math.max(0, batchDelayMillis);
        restoreChunkCount();
    }

    /**
     * 恢复片段总数
     *
     * 这里踩过一个坑，值得记一笔：
     * 原来的实现是"加载时把本次新增的片段数累加到计数器上"（chunkCount.addAndGet）。
     * 看起来没问题，但重复加载同一批文档时——即使向量库因为稳定 ID 而做了覆盖、
     * 数据并没有变多——计数器照样翻了一倍，于是界面上显示出"638 个片段"，
     * 而实际上库里只有 319 条。**数字虚高会让人误以为白白烧了双倍 embedding 额度。**
     *
     * 修法：不再"累加"，而是直接去数真实数据。索引文件是 SimpleVectorStore 序列化出来的，
     * 顶层就是一个 id → 片段 的映射，数它的键数量就是最准的片段总数。
     */
    private void restoreChunkCount() {
        // pgvector 模式：直接以数据库为准（表里 0 条也是真实情况，不用退回统计文件）
        if (!(vectorStore instanceof SimpleVectorStore)) {
            int dbCount = countChunksInDatabase();
            if (dbCount >= 0) {
                chunkCount.set(dbCount);
                log.info("已从 PostgreSQL 恢复知识库统计：{} 个片段", dbCount);
            }
            return;
        }

        int real = countChunksInIndexFile();
        if (real > 0) {
            chunkCount.set(real);
            log.info("已从向量索引恢复知识库统计：{} 个片段", real);
            return;
        }

        // 索引文件不存在或读不出来时，退回伴生统计文件
        File statsFile = new File(indexPath + ".stats");
        if (!statsFile.exists()) {
            return;
        }
        try {
            int restored = Integer.parseInt(Files.readString(statsFile.toPath()).strip());
            chunkCount.set(restored);
            log.info("已恢复知识库统计（来自统计文件）：{} 个片段", restored);
        } catch (Exception e) {
            log.warn("读取索引统计文件失败（不影响检索）：{}", e.getMessage());
        }
    }

    /** 数数据库里有多少片段（只有 pgvector 模式会走到这里） */
    private int countChunksInDatabase() {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) {
            return -1;
        }
        try {
            Integer n = jdbc.queryForObject("select count(*) from " + vectorTable, Integer.class);
            return n == null ? -1 : n;
        } catch (Exception e) {
            log.warn("统计数据库片段数失败（表可能还没建、数据库没启动）：{}", e.getMessage());
            return -1;
        }
    }

    /**
     * 数一数索引文件里到底有多少条片段
     *
     * @return 片段数；文件不存在或解析失败返回 -1
     */
    private int countChunksInIndexFile() {
        File indexFile = new File(indexPath);
        if (!indexFile.exists() || indexFile.length() == 0) {
            return -1;
        }
        try {
            JsonNode root = mapper.readTree(indexFile);
            if (root != null && root.isObject()) {
                return root.size();
            }
        } catch (Exception e) {
            log.warn("统计向量索引条数失败，改用统计文件（不影响检索）：{}", e.getMessage());
        }
        return -1;
    }

    /**
     * 加载一个目录下的所有 md/txt 文档到向量库
     *
     * @param dirPath  文档目录（爬虫的输出目录）
     * @param maxFiles 最多加载几个文件（第一次建议 10 个试水）
     */
    public LoadResult loadDocs(String dirPath, int maxFiles) throws IOException {
        Path dir = Path.of(dirPath);
        if (!Files.isDirectory(dir)) {
            return new LoadResult(false,
                    "目录不存在：" + dir.toAbsolutePath() + "（请先运行 tools/crawl_spring_docs.py 生成文档）",
                    0, chunkCount.get());
        }

        // 1. 收集所有 md / txt 文件
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted()
                    .toList();
        }

        if (files.isEmpty()) {
            return new LoadResult(false, "目录里没有找到 .md / .txt 文件：" + dir.toAbsolutePath(),
                    0, chunkCount.get());
        }

        // 2. 逐个文件处理：读取 → 切分 → 入库
        int loadedFiles = 0;
        int newChunks = 0;

        for (Path file : files) {
            if (loadedFiles >= maxFiles) {
                break;
            }
            String content = Files.readString(file);
            if (content.isBlank()) {
                continue;
            }

            String fileName = file.getFileName().toString();
            String title = extractTitle(content, fileName);

            // metadata 会跟着片段一起存进向量库，检索命中后原样带回来。
            // source 用于追溯原始文件，title 用于给用户看（前端展示引用来源时优先用它）
            Document doc = Document.builder()
                    .text(content)
                    .metadata("source", fileName)
                    .metadata("title", title)
                    .build();

            // 按 token 切分：默认约 800 token 一段，段与段之间有重叠，避免语义被切断。
            // 2.0 里无参 new TokenTextSplitter() 已标记过时，推荐用 builder 显式声明参数——
            // 好处是这几个数字直接暴露在代码里，你调效果时一眼能看到改哪里。
            List<Document> chunks = TokenTextSplitter.builder()
                    .withChunkSize(800)              // 每段目标长度（token）
                    .withMinChunkSizeChars(350)      // 段太短就并进相邻段，避免出现没信息量的碎渣
                    .withMinChunkLengthToEmbed(5)    // 少于 5 个 token 的段直接丢掉
                    .withMaxNumChunks(10000)         // 单篇文档最多切多少段，防止异常大文件
                    .withKeepSeparator(true)         // 保留分隔符，代码块/换行不容易被切坏
                    .build()
                    .apply(List.of(doc));

            // 给每个片段一个"稳定 ID"：文件名 + 序号。
            // 为什么重要？如果 ID 是随机的，重复调用 /api/load 会把同样的内容存第二遍，
            // 检索时同一个片段出现两次，白白占用 topK 名额。用稳定 ID 就是覆盖而非新增。
            List<Document> stableChunks = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                stableChunks.add(chunks.get(i).mutate().id(fileName + "#" + i).build());
            }

            // 分小批入库 + 批间主动节流。
            //
            // 为什么批要小、还要等一下？硅基流动的免费模型有固定速率上限，
            // 87 篇文档切 319 段，如果一口气 8 段一批猛打，很容易撞 429 限速。
            // 有了 RetryEmbeddingModel 兜底重试是能扛过去，但重试会让加载变慢且日志很吵；
            // 主动把请求摊开，整体更快也更稳。（有 Key 额度充足时可以把 delay 调成 0、batch 调大）
            for (int i = 0; i < stableChunks.size(); i += batchSize) {
                vectorStore.add(stableChunks.subList(i, Math.min(i + batchSize, stableChunks.size())));
                if (batchDelayMillis > 0 && i + batchSize < stableChunks.size()) {
                    try {
                        Thread.sleep(batchDelayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("加载文档被中断", ie);
                    }
                }
            }

            newChunks += stableChunks.size();
            loadedFiles++;
        }

        // 3. 落盘：把索引写到本地文件，下次重启直接读回来，不用重新 embedding。
        //    注意这里**不做**"把本次片段数累加到计数器"的动作——
        //    重复加载同一批文档时向量库是覆盖（稳定 ID 生效），累加却会让数字翻倍。
        //    片段总数交给 persistIndex() 在写完之后重新数一遍，永远与磁盘上的真实数据一致。
        String persistNote = persistIndex();

        String message = "加载完成：共 %d 个文件、%d 个片段入库，当前知识库共 %d 个片段。%s"
                .formatted(loadedFiles, newChunks, chunkCount.get(), persistNote);
        log.info(message);

        return new LoadResult(true, message, loadedFiles, chunkCount.get());
    }

    /**
     * 从文档内容里提取一个"给人看的标题"
     *
     * 爬虫存下来的文件名长这样：004-features-ssl.md —— 能追溯，但显示在界面上很难看。
     * 而每篇 Markdown 开头通常就是标题（# 这样的行），直接拿来用最准。
     *
     * 找不到就退回文件名（前端的兜底逻辑还会再美化一次）。
     */
    private static String extractTitle(String content, String fallback) {
        if (content != null) {
            // 只扫开头几行，标题总在最前面，没必要遍历整篇
            for (String line : content.split("\n", 15)) {
                String t = line.strip();
                if (t.startsWith("# ")) {
                    String title = t.substring(2).strip();
                    if (!title.isEmpty()) {
                        return title;
                    }
                } else if (!t.isEmpty() && !t.startsWith("#")) {
                    // 已经进入正文了，说明前面没有标题行
                    break;
                }
            }
        }
        // 文件名兜底：去掉 .md 后缀和开头的数字编号
        String name = fallback.replaceAll("\\.(md|txt)$", "").replaceFirst("^\\d+[-_]", "");
        return name.replace('-', ' ').strip();
    }

    /**
     * 把向量索引保存到本地文件
     *
     * 不成功也不影响使用（索引还在内存里），只是下次重启要重新 embedding，所以只记日志不抛异常。
     */
    private String persistIndex() {
        // pgvector 模式：数据本来就在数据库里，不需要落盘，只要刷新一下计数
        if (!(vectorStore instanceof SimpleVectorStore simple)) {
            int dbCount = countChunksInDatabase();
            if (dbCount >= 0) {
                chunkCount.set(dbCount);
            }
            return "索引已写入 PostgreSQL（pgvector 表 " + vectorTable + "），无需落盘。";
        }

        try {
            File indexFile = new File(indexPath);
            File parent = indexFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return "（索引目录创建失败，本次未落盘）";
            }
            simple.save(indexFile);

            // 落盘后重新数一遍真实条数，顺便把计数器校准到准确值。
            // SimpleVectorStore 没有 size() 这类接口，所以只能反过来数文件。
            int real = countChunksInIndexFile();
            if (real > 0) {
                chunkCount.set(real);
            }

            Files.writeString(new File(indexPath + ".stats").toPath(), String.valueOf(chunkCount.get()));
            return "索引已保存到 " + indexFile.getAbsolutePath() + "，下次启动会自动恢复。";
        } catch (Exception e) {
            log.warn("索引落盘失败：{}", e.getMessage());
            return "（索引落盘失败，不影响本次使用，但重启后需要重新加载）";
        }
    }

    public int getChunkCount() {
        return chunkCount.get();
    }

    public boolean isReady() {
        return chunkCount.get() > 0;
    }

    public String getIndexPath() {
        return new File(indexPath).getAbsolutePath();
    }

    /** 知识库状态，供 /api/status 使用 */
    public IndexStatus status() {
        if (vectorStore instanceof SimpleVectorStore) {
            return new IndexStatus(isReady(), chunkCount.get(), getIndexPath(), new File(indexPath).exists());
        }
        // pgvector 模式没有"索引文件"这个概念，把库表信息告诉前端
        return new IndexStatus(isReady(), chunkCount.get(),
                "PostgreSQL · pgvector · 表 " + vectorTable, true);
    }
}
