<script setup>
import { ref } from 'vue'
import MarkdownText from './MarkdownText.vue'
import { prettyFileName } from '../markdown.js'

/**
 * 引用来源面板 —— 这个项目"引用溯源"能力的界面
 *
 * 回答到底可不可信？点开看看原文就知道了。
 * 这也是 RAG 相比"直接问大模型"最大的优势：有据可查。
 */
const props = defineProps({
  sources: {
    type: Array,
    default: () => []
  }
})

const open = ref(false)

/** 优先用文档自带的标题，没有就美化文件名 */
function displayTitle(s) {
  return s.title || prettyFileName(s.file)
}

/** 相似度转成百分比，用于画进度条 */
function scorePercent(score) {
  if (score == null || isNaN(score)) return 0
  const v = score <= 1 ? score * 100 : score
  return Math.max(4, Math.min(100, Math.round(v)))
}

function scoreText(score) {
  if (score == null || isNaN(score)) return ''
  return (score <= 1 ? score : score / 100).toFixed(3)
}
</script>

<template>
  <div class="sources">
    <button class="toggle" :class="{ open }" @click="open = !open">
      <svg class="icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2">
        <path d="M6.5 3.5H3.5v9h9v-3" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M5 8.5h3M5 10.5h2" stroke-linecap="round" />
        <path d="M9.5 2.5h4v4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      引用来源 {{ sources.length }} 条
      <span class="caret" :class="{ open }"></span>
    </button>

    <div v-if="open" class="list">
      <article v-for="(s, i) in sources" :key="i" class="card">
        <header class="card-head">
          <span class="index">{{ i + 1 }}</span>
          <span class="title" :title="s.file">{{ displayTitle(s) }}</span>
          <span v-if="s.score != null" class="score" :title="'相似度 ' + scoreText(s.score)">
            {{ Math.round(scorePercent(s.score)) }}%
          </span>
        </header>

        <div class="bar">
          <i :style="{ width: scorePercent(s.score) + '%' }"></i>
        </div>

        <div class="snippet">
          <MarkdownText :text="s.snippet" variant="snippet" />
        </div>

        <footer class="card-foot">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.2">
            <path d="M3.5 2.5h6l3 3v8h-9z" stroke-linejoin="round" />
            <path d="M9.5 2.5v3h3" stroke-linejoin="round" />
          </svg>
          {{ s.file }}
        </footer>
      </article>
    </div>
  </div>
</template>

<style scoped>
.sources {
  margin-top: 10px;
}

/* ===== 折叠开关 ===== */
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  padding: 4px 0;
  font-size: 12px;
  color: var(--text-secondary);
  transition: color 0.15s;
}

.toggle:hover,
.toggle.open {
  color: var(--primary);
}

.icon {
  width: 14px;
  height: 14px;
}

.caret {
  width: 0;
  height: 0;
  border-left: 4px solid currentColor;
  border-top: 3.5px solid transparent;
  border-bottom: 3.5px solid transparent;
  transition: transform 0.15s;
  margin-left: 2px;
}

.caret.open {
  transform: rotate(90deg);
}

/* ===== 卡片列表 ===== */
.list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.card {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fbfcfd;
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px 7px;
}

.index {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 5px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.score {
  flex-shrink: 0;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  color: var(--primary);
  background: var(--primary-soft);
  padding: 1px 7px;
  border-radius: 999px;
}

/* 相似度进度条 */
.bar {
  height: 2px;
  background: var(--border);
  margin: 0 12px;
  border-radius: 2px;
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  background: var(--primary);
  border-radius: 2px;
}

/* 原文片段 */
.snippet {
  padding: 9px 12px 2px;
  max-height: 190px;
  overflow-y: auto;
}

.card-foot {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px 9px;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: var(--text-tertiary);
  word-break: break-all;
}
</style>
