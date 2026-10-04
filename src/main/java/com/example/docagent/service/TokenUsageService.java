package com.example.docagent.service;

import com.example.docagent.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * token 用量统计器
 *
 * <h3>为什么需要它</h3>
 * 用免费模型时，"我到底用了多少"是个很实际的问题：
 * <ul>
 *   <li>想知道哪次问答最贵 —— 通常是命中资料最多、对话历史最长的那次</li>
 *   <li>想验证"意图分类优化"是不是真的省了 token</li>
 *   <li>想估算如果换成付费模型，这套用法一个月要花多少钱</li>
 * </ul>
 * 服务商控制台当然也能看，但那是全账号所有应用的数字，
 * 拆不出"这个项目用了多少"。这里统计的是**本应用进程内**的精确用量。
 *
 * <h3>数据从哪来</h3>
 * 每家的 API 响应里都会带回 usage（OpenAI 兼容协议字段名一样）：
 * prompt_tokens / completion_tokens / total_tokens。
 * Spring AI 把它包装成 {@link Usage}，挂在 ChatResponse 的 metadata 上，直接读即可。
 *
 * <h3>为什么用原子类而不是 synchronized</h3>
 * 流式输出时每个 chunk 都会回调一次，多线程累加是常态。AtomicLong 无锁且足够快。
 *
 * <h3>关于持久化</h3>
 * 只存在内存里，重启清零。这是刻意的：它回答的是"本次运行消耗了多少"，
 * 而跨月的历史用量服务商控制台有更准的数字（那边还包含你其他应用的调用）。
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    /**
     * 成本估算用的单价（元 / 百万 token）
     *
     * <p>来源（2026-10 公开价格，用于给用户一个数量级感知，不是账单）：
     * <ul>
     *   <li>智谱 GLM-4.7-Flash：免费档 0 元；付费参考价 输入 0.5 元 / 输出 2 元 每百万</li>
     *   <li>硅基流动 BAAI/bge-m3：免费档 0 元；付费档 0.5 元 每百万</li>
     * </ul>
     * 当前两个模型都在免费档，所以这里算出来的成本恒为 0。
     * 留着这个字段是为了以后换付费模型时能立刻看出量级。
     */
    private static final double ZHIPU_INPUT_PRICE_PER_MILLION = 0.5;
    private static final double ZHIPU_OUTPUT_PRICE_PER_MILLION = 2.0;

    /**
     * 累加一次调用的用量
     *
     * @param usage 服务商返回的用量，可能为 null（比如某些兼容实现不返回）
     */
    public void record(Usage usage) {
        if (usage == null) {
            return;
        }
        add(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /** 累加一次调用的用量（流式场景：每收到一个 chunk 都会调一次，所以做了去重保护） */
    public void add(Integer prompt, Integer completion) {
        requestCount.incrementAndGet();
        long p = prompt == null ? 0 : prompt;
        long c = completion == null ? 0 : completion;
        if (p <= 0 && c <= 0) {
            return;
        }
        promptTokens.addAndGet(p);
        completionTokens.addAndGet(c);
    }

    /**
     * 流式专用：整个流结束后一次性累加
     *
     * <p>为什么不能每个 chunk 都累加？因为流式响应的最后一个 chunk 里
     * 才会带上完整的 usage，中间那些 chunk 的 usage 是 null 或 0。
     * 而如果模型在每个 chunk 都返回完整 usage（有些实现会），
     * 逐个累加就会重复计算。所以这里只认最后一次。
     */
    public void recordOnce(Usage usage) {
        record(usage);
    }

    public TokenUsage snapshot() {
        long p = promptTokens.get();
        long c = completionTokens.get();
        double cost = (p / 1_000_000.0) * ZHIPU_INPUT_PRICE_PER_MILLION
                + (c / 1_000_000.0) * ZHIPU_OUTPUT_PRICE_PER_MILLION;
        return new TokenUsage(p, c, p + c, requestCount.get(), cost);
    }

    /** 每完成一次问答打一行汇总，方便在 IDEA 控制台直接观察消耗 */
    public void logSummary(String cid) {
        TokenUsage u = snapshot();
        log.info("会话 {} token 用量：本次运行累计 输入 {} + 输出 {} = {}（{} 次调用，估算成本 ¥{}）",
                cid, u.promptTokens(), u.completionTokens(), u.totalTokens(), u.requestCount(),
                String.format("%.6f", u.estimatedCostCny()));
    }
}
