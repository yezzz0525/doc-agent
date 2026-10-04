<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../markdown.js'

/**
 * Markdown 渲染组件
 *
 * 把模型返回的 Markdown 源码转成真正的 HTML。
 * variant="snippet" 时字号调小，用于引用来源里的原文片段。
 * streaming=true 时在正文末尾追加一个光标元素（流式输出期间正文一直在长）。
 */
const props = defineProps({
  text: { type: String, default: '' },
  variant: { type: String, default: '' },
  streaming: { type: Boolean, default: false }
})

const html = computed(
  () => renderMarkdown(props.text) + (props.streaming ? '<span class="typing-caret"></span>' : '')
)
</script>

<template>
  <div class="markdown-body" :class="variant" v-html="html"></div>
</template>
