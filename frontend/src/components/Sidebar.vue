<script setup>
import { ref, computed, nextTick } from 'vue'

/**
 * 左侧边栏：会话管理 + 知识库状态
 *
 * 它持有什么？什么都不持有 —— 数据全部由父组件（App.vue）传进来，
 * 用户的操作也只是"往上抛事件"（emit），真正改数据的是 App。
 *
 * 这是 Vue 里的重要约定：**数据往下流，事件往上传**。
 * 好处是这个组件可以随便复用、随便测，不关心数据从哪来。
 */
const props = defineProps({
  conversations: { type: Array, default: () => [] },
  currentId: { type: String, default: '' },
  status: { type: Object, default: () => ({ ready: false, chunkCount: 0 }) },
  loadingDocs: { type: Boolean, default: false },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'create', 'remove', 'rename', 'load'])

/** 正在重命名的会话 ID（为空表示没有在改名） */
const editingId = ref('')
const editingTitle = ref('')
const editInput = ref(null)

// ==================== 时间与分组 ====================

function bucketOf(iso) {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '更早'
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const that = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const days = Math.round((today - that) / 86400000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  return '更早'
}

/** 按时间分组显示，比一长条平铺更容易找 */
const groups = computed(() => {
  const buckets = { 今天: [], 昨天: [], 更早: [] }
  for (const c of props.conversations) {
    buckets[bucketOf(c.updatedAt)].push(c)
  }
  return ['今天', '昨天', '更早']
    .map((label) => ({ label, items: buckets[label] }))
    .filter((g) => g.items.length)
})

function timeAgo(iso) {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return min + ' 分钟前'
  const hours = Math.floor(min / 60)
  if (hours < 24) return hours + ' 小时前'
  const days = Math.floor(hours / 24)
  if (days < 7) return days + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

// ==================== 重命名 ====================

async function startRename(conv) {
  editingId.value = conv.id
  editingTitle.value = conv.title
  await nextTick()
  // ref 写在 v-for 里时，Vue 收集到的是数组
  const els = Array.isArray(editInput.value) ? editInput.value : [editInput.value]
  els[0]?.focus()
  els[0]?.select()
}

function commitRename() {
  const id = editingId.value
  const title = editingTitle.value.trim()
  editingId.value = ''
  if (id && title) {
    emit('rename', id, title)
  }
}

function cancelRename() {
  editingId.value = ''
}

function onSelect(id) {
  if (editingId.value) return
  emit('select', id)
}
</script>

<template>
  <aside class="sidebar">
    <!-- 品牌区 -->
    <div class="brand">
      <div class="logo">S</div>
      <div class="brand-text">
        <div class="brand-title">Spring 文档助手</div>
        <div class="brand-sub">答案来自官方文档</div>
      </div>
    </div>

    <!-- 新对话 -->
    <button class="new-chat" @click="emit('create')">
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
        <path d="M8 3.5v9M3.5 8h9" stroke-linecap="round" />
      </svg>
      新对话
    </button>

    <!-- 历史会话 -->
    <div class="list">
      <template v-for="g in groups" :key="g.label">
        <div class="group-label">{{ g.label }}</div>

        <div
          v-for="c in g.items"
          :key="c.id"
          class="item"
          :class="{ active: c.id === currentId }"
          @click="onSelect(c.id)"
        >
          <template v-if="editingId === c.id">
            <input
              ref="editInput"
              v-model="editingTitle"
              class="rename-input"
              @click.stop
              @keydown.enter.prevent="commitRename"
              @keydown.esc="cancelRename"
              @blur="commitRename"
            />
          </template>

          <template v-else>
            <div class="item-main">
              <div class="item-title">{{ c.title }}</div>
              <div class="item-meta">{{ c.messageCount }} 条消息 · {{ timeAgo(c.updatedAt) }}</div>
            </div>

            <div class="item-actions">
              <button class="mini" title="重命名" @click.stop="startRename(c)">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2">
                  <path d="M11.2 2.8l2 2L5.5 12.5H3.5v-2z" stroke-linejoin="round" />
                </svg>
              </button>
              <button class="mini danger" title="删除" @click.stop="emit('remove', c)">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2">
                  <path d="M3 5h10M6.5 5V3.5h3V5M4.8 5l.6 8h5.2l.6-8" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
            </div>
          </template>
        </div>
      </template>

      <div v-if="!conversations.length" class="list-empty">
        还没有历史对话<br />
        <span>问第一个问题就会自动保存</span>
      </div>
    </div>

    <!-- 底部：知识库状态 -->
    <div class="foot">
      <div class="kb">
        <span class="dot" :class="{ ok: status.ready }"></span>
        <div class="kb-text">
          <div class="kb-line">{{ status.ready ? '知识库就绪' : '知识库为空' }}</div>
          <div class="kb-sub">
            {{ status.ready ? status.chunkCount + ' 个文档片段' : '先加载文档才能提问' }}
          </div>
        </div>
      </div>

      <button class="load-btn" :disabled="loadingDocs" @click="emit('load')">
        {{ loadingDocs ? '加载中…' : status.ready ? '重新加载' : '加载文档' }}
      </button>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border);
  height: 100%;
}

/* ===== 品牌 ===== */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 12px;
}

.logo {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 500;
  font-size: 15px;
  flex-shrink: 0;
}

.brand-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.3;
}

.brand-sub {
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.4;
}

/* ===== 新对话按钮 ===== */
.new-chat {
  margin: 0 12px 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 12px;
  border: 1px solid var(--border-strong);
  border-radius: 9px;
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
  transition: all 0.15s;
}

.new-chat:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
}

.new-chat svg {
  width: 14px;
  height: 14px;
}

/* ===== 会话列表 ===== */
.list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.group-label {
  padding: 10px 8px 4px;
  font-size: 11px;
  color: var(--text-tertiary);
}

.item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.12s;
}

.item:hover {
  background: var(--surface-hover);
}

.item.active {
  background: var(--surface-active);
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-title {
  font-size: 13px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item.active .item-title {
  color: var(--primary);
}

.item-meta {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 1px;
}

/* 悬停才出现的操作按钮，平时保持界面干净 */
.item-actions {
  display: none;
  gap: 2px;
  flex-shrink: 0;
}

.item:hover .item-actions {
  display: flex;
}

.mini {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  border-radius: 5px;
  color: var(--text-tertiary);
  transition: all 0.12s;
}

.mini:hover {
  background: var(--surface);
  color: var(--text);
}

.mini.danger:hover {
  color: #c0392b;
}

.mini svg {
  width: 13px;
  height: 13px;
}

.rename-input {
  width: 100%;
  border: 1px solid var(--primary);
  border-radius: 6px;
  padding: 4px 7px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text);
  outline: none;
  background: var(--surface);
}

.list-empty {
  padding: 28px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
  line-height: 1.9;
}

.list-empty span {
  font-size: 11px;
  opacity: 0.75;
}

/* ===== 底部状态 ===== */
.foot {
  border-top: 1px solid var(--border);
  padding: 12px;
}

.kb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c4c9d0;
  flex-shrink: 0;
}

.dot.ok {
  background: #2f9e44;
}

.kb-line {
  font-size: 12px;
  line-height: 1.4;
}

.kb-sub {
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.4;
}

.load-btn {
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: 8px;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  transition: background 0.15s;
}

.load-btn:hover:not(:disabled) {
  background: var(--primary-hover);
}
</style>
