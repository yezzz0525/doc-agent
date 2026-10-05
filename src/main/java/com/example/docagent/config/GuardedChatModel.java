package com.example.docagent.config;

import com.example.docagent.service.TokenUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话模型守卫：串行化 + 排队超时 + token 统计
 *
 * <h3>为什么必须做这件事</h3>
 * <b>部分免费档服务商只允许 1 个并发请求</b>（如智谱 GLM 公开的免费档限制；百炼 qwen-flash 宽松得多，但保留这层保护不亏——不是我们配置错了）。
 * 而一次问答在优化前要调它两次（意图分类 + 回答生成），两次之间还夹着向量检索。
 * 如果上一次请求还没结束（比如流式输出途中）就发起新请求，必然撞 429。
 *
 * <p><b>与其"撞了再重试"，不如"根本别并发"</b>——这是本类和
 * {@link RetryEmbeddingModel} 同一个思路：客户端自己排队，从源头避免被拒。
 *
 * <h3>三个关键设计</h3>
 * <ol>
 *   <li><b>流式请求必须持有信号量到流结束</b>。服务商的"1 并发"指的是同时进行的 HTTP 请求，
 *       而流式请求在收到最后一个 chunk 前一直算进行中。如果只在 subscribe 瞬间 acquire、
 *       立刻 release，那么两个流式请求还是会同时打到同一个服务商。
 *       这里用 {@code Flux.defer + doFinally} 保证"拿到信号量 → 订阅 → 流结束/取消 → 归还"，
 *       严格配对，不会漏还也不会双还。</li>
 *   <li><b>排队要设超时</b>。万一有个流式请求卡住 3 分钟（前端 SSE 超时上限），
 *       信号量会被占 3 分钟，后面全在干等。所以用 tryAcquire(超时) 而不是 acquire()，
 *       拿不到就立刻失败并给出人话提示。</li>
 *   <li><b>token 统计顺手做掉</b>。既然每次响应都拿到了，顺手累加，
 *       不用再单独包一层去截 usage。</li>
 * </ol>
 *
 * <h3>为什么流式不重试</h3>
 * 一旦已经推送了部分文字，重试会导致内容重复，用户看到两遍答案。
 * 而串行化之后本来就不会再撞 429，所以流式干脆不重试——保持简单、行为可预测。
 */
public class GuardedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GuardedChatModel.class);

    private final ChatModel delegate;
    private final Semaphore singleSlot = new Semaphore(1, true);
    private final long acquireTimeoutSeconds;
    private final TokenUsageService usageService;

    /** 正在排队的请求数，用来给用户更准确的提示 */
    private final AtomicReference<String> currentHolder = new AtomicReference<>(null);

    public GuardedChatModel(ChatModel delegate, TokenUsageService usageService,
                            long acquireTimeoutSeconds) {
        this.delegate = delegate;
        this.usageService = usageService;
        this.acquireTimeoutSeconds = acquireTimeoutSeconds;
    }

    // ==================== 非流式：意图分类走这里 ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        acquireOrThrow();
        try {
            ChatResponse response = delegate.call(prompt);
            if (response != null && response.getMetadata() != null) {
                usageService.record(response.getMetadata().getUsage());
            }
            return response;
        } finally {
            releaseSlot();
        }
    }

    // ==================== 流式：回答生成走这里 ====================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // defer：每次订阅才真正 acquire。ChatClient 内部只订阅一次，
        // 但用 defer 能保证语义清晰，也避免构造 Flux 时就阻塞调用线程。
        return Flux.defer(() -> {
            if (!tryAcquireSlot()) {
                return Flux.error(new IllegalStateException(
                        "上一个回答还在生成中，请等它结束再发新问题"
                                + "（免费模型只允许 1 个并发请求）"));
            }
            return delegate.stream(prompt)
                    .doOnNext(chunk -> {
                        if (chunk != null && chunk.getMetadata() != null) {
                            usageService.record(chunk.getMetadata().getUsage());
                        }
                    })
                    // doFinally 在流正常结束、异常、取消三种情况下都会触发，
                    // 正好对应"请求真的结束了"——SSE 连接被浏览器断开时也会走到这里
                    .doFinally(signal -> releaseSlot());
        });
    }

    // ==================== 必须委托的配置方法（踩过坑，务必保留） ====================

    /**
     * 下面两个方法<b>必须原样委托给被包装对象</b>，否则会踩一个非常隐蔽的坑：
     *
     * <pre>
     * java.lang.ClassCastException: class org.springframework.ai.chat.prompt.DefaultChatOptions
     *   cannot be cast to class org.springframework.ai.ai.openai.OpenAiChatOptions
     *   at OpenAiChatModel.createRequest(OpenAiChatModel.java:683)
     * </pre>
     *
     * 原因链：ChatClient 构造请求时，会拿 {@code chatModel.getDefaultOptions()} 作为底子
     * 去合并用户设置的参数。而 {@code ChatModel} 接口的 default 实现返回的是
     * <b>通用的 {@code DefaultChatOptions}</b>，不是 OpenAI 专属的 {@code OpenAiChatOptions}。
     * 一旦包装类没把这个方法转发出去，ChatClient 合并出来的就是通用类型，
     * 而 {@code OpenAiChatModel.createRequest} 内部直接强转成 {@code OpenAiChatOptions} → 崩。
     *
     * <p>一句话总结这个坑：**包装 Spring AI 的模型接口时，凡是和"具体配置类型"有关的方法，
     * 一个都不能漏。** 漏了不会编译报错，只在运行时 ClassCastException。
     *
     * <p>注意：IDE 会提示 {@code getDefaultOptions()} 已过时并标记为待删除。
     * 但<b>不能删</b>——Spring AI 2.0 的 {@code DefaultChatClient} 内部仍在调用它来合并参数，
     * 一旦不转发就会重现上面那个 ClassCastException。等 Spring AI 3.x 彻底移除后再删。
     */
    @SuppressWarnings("deprecation") // 2.0 的 ChatClient 仍依赖此方法，3.0 才能移除
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    // ==================== 信号量 ====================

    /** 非流式调用：拿不到就抛异常（意图分类失败会降级，不影响用户） */
    private void acquireOrThrow() {
        if (!tryAcquireSlot()) {
            throw new IllegalStateException(
                    "等待模型响应超时（" + acquireTimeoutSeconds + " 秒）。"
                            + "可能是上一次请求卡住了，重启应用再试。");
        }
    }

    private boolean tryAcquireSlot() {
        try {
            return singleSlot.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 归还并发槽
     *
     * 加了一层 availablePermits 判断是为了幂等：万一某条异常路径重复调用了 release，
     * 也不会把信号量放成 2 个permit（那会导致真正的并发，进而绕过 1 并发的保护）。
     */
    private void releaseSlot() {
        if (singleSlot.availablePermits() == 0) {
            singleSlot.release();
        }
    }

    /** 供健康检查用：当前是否有请求在跑 */
    public boolean isBusy() {
        return singleSlot.availablePermits() == 0;
    }

    @Override
    public String toString() {
        return "GuardedChatModel(" + delegate.getClass().getSimpleName() + ")";
    }
}
