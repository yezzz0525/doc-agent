<script setup>
import { ref, computed } from 'vue'
import SourceList from './SourceList.vue'
import MarkdownText from './MarkdownText.vue'

/**
 * 一条消息
 *
 * message 结构：
 *   { role: 'user' | 'assistant', text: '内容', sources: [...], mode: 'rag' | 'chat', pending: true/false }
 *
 * 两种角色的呈现方式刻意做得不一样：
 * - 用户消息：蓝色气泡靠右，一眼看出"这是我说的"
 * - 助手消息：不套气泡，直接按文档排版铺开，长回答读起来像一篇博客文章
 *
 * mode 是这次新加的：后端会告诉我们这条回答是"查了文档"还是"普通聊天"，
 * 顺手在下面标出来——不然用户会疑惑"为什么这次没有引用来源"。
 * 报错消息不带 mode，所以标签自然不显示。
 */
const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

const copied = ref(false)

const isUser = computed(() => props.message.role === 'user')

/** 回答模式：'rag'（查了文档）/ 'chat'（闲聊）/ undefined（报错等） */
const mode = computed(() => props.message.mode)

const modeLabel = computed(() =>
  mode.value === 'chat' ? '普通对话 · 未查文档' : '基于 Spring 文档'
)

/** 复制回答内容（Markdown 源码），方便贴到笔记里 */
async function copy() {
  try {
    await navigator.clipboard.writeText(props.message.text || '')
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // 浏览器不给权限时静默失败，不打扰用户
  }
}
</script>

<template>
  <div class="row" :class="message.role">
    <div v-if="!isUser" class="avatar">
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.3">
        <path d="M10 2.5l6.5 3.8v7.4L10 17.5l-6.5-3.8V6.3z" stroke-linejoin="round" />
        <path d="M10 10.2l6.2-3.6M10 10.2v7M10 10.2L3.8 6.6" stroke-linejoin="round" />
      </svg>
    </div>

    <div class="body">
      <!-- 用户消息：纯文本，不做 Markdown 渲染（避免用户输入的符号被当成语法） -->
      <div v-if="isUser" class="bubble">{{ message.text }}</div>

      <!-- 助手消息：Markdown 渲染 -->
      <template v-else>
        <div v-if="message.pending || message.step" class="pending">
          <span class="thinking"><i></i><i></i><i></i></span>
          <!-- ReAct 循环会不断送来新的步骤提示，直接显示最新的那条；
               还没收到步骤时（检索还没开始）用通用文案兜底 -->
          <span class="pending-text">{{ message.step || '正在判断意图并检索文档…' }}</span>
        </div>

        <template v-else>
          <!-- streaming=true 时，正文末尾会多一个闪烁光标，表示"还在写" -->
          <MarkdownText :text="message.text" :streaming="!!message.streaming" />

          <!-- 工具条等整段回答写完再出现，免得打字途中就能点复制只复制半句话 -->
          <div class="tools" v-if="!message.streaming">
            <span v-if="mode" class="mode-tag" :class="mode">
              <i class="dot"></i>{{ modeLabel }}
            </span>

            <button class="tool" :title="copied ? '已复制' : '复制回答'" @click="copy">
              <svg v-if="!copied" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2">
                <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
                <path d="M10.5 5.5V4a1.5 1.5 0 00-1.5-1.5H4A1.5 1.5 0 002.5 4v5A1.5 1.5 0 004 10.5h1.5" stroke-linecap="round" />
              </svg>
              <svg v-else viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
                <path d="M3.5 8.5l3 3 6-7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              {{ copied ? '已复制' : '复制' }}
            </button>
          </div>

          <SourceList v-if="message.sources && message.sources.length" :sources="message.sources" />
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.row.user {
  justify-content: flex-end;
}

/* AI 头像 */
.avatar {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-soft);
  color: var(--primary);
  margin-top: 2px;
}

.avatar svg {
  width: 16px;
  height: 16px;
}

.body {
  min-width: 0;
  flex: 1;
}

/* 助手消息铺满可用宽度，读长文更舒服 */
.row.assistant .body {
  max-width: 100%;
}

/* 用户气泡 */
.bubble {
  display: inline-block;
  max-width: 100%;
  padding: 9px 14px;
  border-radius: 14px 14px 4px 14px;
  background: var(--primary);
  color: #fff;
  white-space: pre-wrap;
  word-break: break-word;
}

.row.user .body {
  display: flex;
  justify-content: flex-end;
  flex: 0 1 auto;
  max-width: 76%;
}

/* 思考中 */
.pending {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.pending-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.thinking {
  display: inline-flex;
  gap: 4px;
}

.thinking i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary);
  opacity: 0.5;
  animation: blink 1.2s infinite ease-in-out;
}

.thinking i:nth-child(2) {
  animation-delay: 0.15s;
}

.thinking i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes blink {
  0%,
  60%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

/* 消息下方的小工具条 */
.tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  padding: 3px 7px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-tertiary);
  transition: all 0.15s;
}

.tool:hover {
  background: var(--surface-hover);
  color: var(--text-secondary);
}

.tool svg {
  width: 13px;
  height: 13px;
}

/* 回答模式标签：让"这次查没查文档"一目了然 */
.mode-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  line-height: 1;
  padding: 4px 9px;
  border-radius: 20px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-tertiary);
}

.mode-tag .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.6;
}

.mode-tag.rag {
  border-color: transparent;
  background: var(--primary-soft);
  color: var(--primary);
}

.mode-tag.chat .dot {
  opacity: 0.9;
}

/*
 * 流式输出时的打字光标
 * 它是 v-html 渲染出来的内容，不在本组件的模板里，所以要用 :deep 才能命中
 */
.body :deep(.typing-caret) {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 3px;
  vertical-align: -2px;
  border-radius: 1px;
  background: var(--primary);
  animation: caret 1s steps(2, start) infinite;
}

@keyframes caret {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .body :deep(.typing-caret) {
    animation: none;
  }
}
</style>
