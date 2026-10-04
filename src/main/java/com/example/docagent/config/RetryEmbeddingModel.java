package com.example.docagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 带重试的 EmbeddingModel 装饰器
 *
 * <h3>为什么需要它</h3>
 * 我们用硅基流动的免费模型 BAAI/bge-m3 做向量化，它的免费档位有<b>固定的速率上限</b>。
 * 加载 87 篇文档会切成 319 段、分批调用 embedding 接口，短时间打出 40 次请求，
 * 很容易撞上限：
 *
 * <pre>
 * com.openai.errors.RateLimitException: 429: 您的账户已达速率限制，请控制请求频率
 * </pre>
 *
 * 而 429 是"稍等一下就好"的错误，不是"请求错了"。只要等 1~2 秒重发就能成功，
 * 没必要让整个任务失败。所以在这里统一加上<b>指数退避重试</b>。
 *
 * <h3>为什么要写成装饰器而不是改业务代码</h3>
 * 调 embedding 的地方有两处（DocumentService 加载文档、RagChatService 检索），
 * 而且真正调模型的是 Spring AI 内部的 PgVectorStore，我们插不上手。
 * 包一层装饰器的好处：<b>所有经过的 embedding 调用都自动获得重试能力</b>，
 * 业务代码一行不用改，以后换模型也不用重新加。
 *
 * <h3>重试策略</h3>
 * <ul>
 *   <li>429（限速）/ 5xx（服务端错误）/ IO 异常 → 值得重试</li>
 *   <li>401（Key 错）、400（参数错）→ 重试多少次都一样，直接失败，不浪费 3 次等待</li>
 *   <li>等待时间：1s → 2s → 4s，<b>每次再叠加 0~500ms 随机抖动</b>。
 *       加抖动是为了避免"多个请求同时被限流后同时重发，再次同时触发限流"（惊群效应）</li>
 * </ul>
 */
public class RetryEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(RetryEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final int maxRetries;
    private final long baseDelayMillis;

    /**
     * 客户端限速：每秒最多允许的 embedding 请求数。<=0 表示不限速。
     *
     * 为什么"被限速"要自己先限？服务端拒绝是"出了错再补救"，
     * 而客户端排队是"根本没打超"。体验上前者要等重试 + 可能失败，
     * 后者只是慢一点但一定成功。
     */
    private final double qps;

    /** 令牌桶剩余令牌数，跨线程共享，所以所有读写都在 synchronized 里 */
    private double availablePermits = 0;

    private long lastRefillNanos = System.nanoTime();

    public RetryEmbeddingModel(EmbeddingModel delegate, int maxRetries, long baseDelayMillis) {
        this(delegate, maxRetries, baseDelayMillis, 0);
    }

    public RetryEmbeddingModel(EmbeddingModel delegate, int maxRetries, long baseDelayMillis, double qps) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.qps = qps;
        this.availablePermits = Math.max(1, qps);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        int size = request.getInstructions() == null ? 0 : request.getInstructions().size();
        return withRetry("batch=" + size + " 条", () -> delegate.call(request));
    }

    /**
     * 2.0 里这个方法在接口上还是抽象的（虽然已不推荐直接用），必须实现。
     * 走同一套重试逻辑，保证任何调用方式都有保护。
     */
    @Override
    public float[] embed(Document document) {
        return withRetry("单条文档", () -> delegate.embed(document));
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String toString() {
        return "RetryEmbeddingModel(" + delegate.getClass().getSimpleName() + ")";
    }

    // ==================== 客户端限速（令牌桶） ====================

    /**
     * 取一个"通行证"再发请求。
     *
     * 令牌桶的逻辑：令牌按 qps 的速度匀速补充，取走一个就要等下一个生成。
     * 这样无论上层循环多快，实际打出去的请求速度都被压到 qps 以内，
     * 永远不会撞上服务端的速率上限。
     *
     * 为什么不用 Guava 的 RateLimiter？为了不给项目引入新依赖。
     * 这里的实现是标准令牌桶，够用且可读。
     */
    private void acquirePermit(String what) {
        if (qps <= 0) {
            return;
        }
        long waitMillis;
        synchronized (this) {
            long now = System.nanoTime();
            // 按流逝时间补充令牌，桶上限就是 qps（允许短时间攒一小批，突发时能连发几个）
            double replenished = (now - lastRefillNanos) / 1_000_000_000.0 * qps;
            availablePermits = Math.min(qps, availablePermits + replenished);
            lastRefillNanos = now;

            if (availablePermits >= 1) {
                availablePermits -= 1;
                return;
            }
            // 令牌不够：要等的时间 = 差多少令牌 / 每秒产多少令牌
            waitMillis = (long) Math.ceil((1 - availablePermits) / qps * 1000);
        }
        // wait 必须在 synchronized 块【外面】，否则会占着锁挡住其他线程
        log.debug("客户端限速排队中（qps={}），等待 {} ms（{}）", qps, waitMillis, what);
        sleep(waitMillis);
    }

    // ==================== 内部工具 ====================

    private <T> T withRetry(String what, Supplier<T> action) {
        Exception last = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 每次真正发请求【之前】先过限速闸门，重试也要重新排一次队
            acquirePermit(what);
            try {
                T result = action.get();
                if (attempt > 1) {
                    log.info("embedding 调用在第 {} 次重试后成功（{}）", attempt, what);
                }
                return result;
            } catch (Exception e) {
                last = e;

                boolean canRetry = attempt < maxRetries && isRetryable(e);
                if (!canRetry) {
                    if (isRateLimited(e)) {
                        log.warn("embedding 连续 {} 次都被限速，已放弃：{}", attempt, rootMessage(e));
                    } else {
                        log.error("embedding 调用失败且不可重试：{}", rootMessage(e));
                    }
                    // 原样抛出去，保留异常类型（上层还要靠 RateLimitException / UnauthorizedException
                    // 区分错误类型并给出不同提示），checked exception 才包一层。
                    if (e instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("embedding 调用失败", e);
                }

                long delay = backoffMillis(attempt);
                log.warn("embedding 调用遇到 {}（{}），第 {}/{} 次重试，等待 {} ms",
                        shortReason(e), rootMessage(e), attempt, maxRetries, delay);
                sleep(delay);
            }
        }

        // 理论上走不到这里（循环内要么 return 要么 throw），保险起见别让方法"没有返回值"
        if (last instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("embedding 重试逻辑异常", last);
    }

    /**
     * 判断这个错误值不值得重试。
     *
     * 关键区分：
     *   429 / 5xx / 网络超时 —— 换个时间重发就可能成
     *   401 / 403 / 400      —— 是配置或请求本身有问题，重试 100 次也是同样结果，纯浪费 7 秒
     */
    private boolean isRetryable(Throwable e) {
        String msg = rootMessage(e).toLowerCase();
        String type = e.getClass().getSimpleName();

        if (type.contains("RateLimit") || msg.contains("429")
                || msg.contains("rate limit") || msg.contains("速率限制") || msg.contains("限速")) {
            return true;
        }
        if (type.contains("InternalServer") || msg.contains(" 500") || msg.contains(" 502")
                || msg.contains(" 503") || msg.contains(" 504") || msg.contains("timeout")) {
            return true;
        }
        if (e instanceof java.io.IOException) {
            return true;
        }
        // 明确的客户端错误：Key 不对、参数不对、余额不足……都不重试
        if (type.contains("Unauthorized") || type.contains("BadRequest")
                || type.contains("PermissionDenied") || msg.contains(" 401")
                || msg.contains(" 403") || msg.contains("余额不足")) {
            return false;
        }
        // 没把握的类型，默认不重试——宁可快速失败让人看到原始报错
        return false;
    }

    private boolean isRateLimited(Throwable e) {
        String msg = rootMessage(e).toLowerCase();
        return e.getClass().getSimpleName().contains("RateLimit")
                || msg.contains("429") || msg.contains("速率限制") || msg.contains("限速");
    }

    /** 退避 + 随机抖动，避免惊群 */
    private long backoffMillis(int attempt) {
        long base = baseDelayMillis * (1L << (attempt - 1));
        return base + ThreadLocalRandom.current().nextLong(0, 500);
    }

    /** 取最内层异常信息，比包装层的 "java.lang.Exception: ..." 更有用 */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }

    private String shortReason(Throwable e) {
        if (isRateLimited(e)) {
            return "限速 429";
        }
        return e.getClass().getSimpleName();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试时被中断", ie);
        }
    }
}
