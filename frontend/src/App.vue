<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ChatMessage from './components/ChatMessage.vue'
import { api } from './api.js'

/**
 * 应用主组件 —— 负责全部状态的持有与数据流转
 *
 * 布局分左右两块：
 *   左：Sidebar（会话列表、知识库状态）—— 只负责显示和抛事件
 *   右：聊天区（消息列表、输入框）—— 就是这里
 *
 * 数据流向永远是单向的：App 持有状态 → 传给子组件 → 子组件 emit 事件 → App 改状态。
 * 这样调试时只要盯住这一个文件，就能知道数据是怎么变的。
 */

// ==================== 状态 ====================
const conversations = ref([])      // 左侧会话列表（摘要）
const currentId = ref('')          // 当前会话 ID，空表示"尚未落库的新对话"
const messages = ref([])           // 当前会话的消息
const input = ref('')              // 输入框内容
const loading = ref(false)         // 是否正在等模型回答
const loadingConv = ref(false)     // 是否正在加载某个历史会话
const loadingDocs = ref(false)     // 是否正在加载文档
const status = ref({ ready: false, chunkCount: 0 })
const usage = ref(null)           // token 用量（本次运行累计）
const toast = ref('')              // 轻提示文字
const listEl = ref(null)           // 消息容器（用于滚到底部）
const inputEl = ref(null)          // 输入框（用于聚焦）

let toastTimer = null

/** 顶栏显示的标题 */
const currentTitle = computed(() => {
  const c = conversations.value.find((x) => x.id === currentId.value)
  return c ? c.title : '新对话'
})

/** 空状态下展示的示例问题，点一下直接提问 */
const examples = [
  'Spring Boot 怎么配置 SSL？',
  '如何用 Profile 区分不同环境？',
  'Actuator 有哪些常用端点？',
  '怎么自定义一个 starter？',
  '你是谁？'
]

// ==================== 通用小工具 ====================

function notify(text, duration = 3200) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), duration)
}

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) {
    listEl.value.scrollTop = listEl.value.scrollHeight
  }
}

// ==================== 数据加载 ====================

async function fetchStatus() {
  try {
    status.value = await api.status()
  } catch (e) {
    console.error('获取知识库状态失败', e)
  }
}

async function fetchConversations() {
  try {
    conversations.value = await api.conversations()
  } catch (e) {
    console.error('获取会话列表失败', e)
  }
}

/**
 * 拉取 token 用量
 *
 * 用免费模型时没有账单可查，但"用了多少"必须看得见 ——
 * 它能直观告诉你哪次问答最贵（通常是命中资料最多、历史最长的那次），
 * 也能验证"意图分类走规则"这类优化到底省没省。
 */
async function fetchUsage() {
  try {
    usage.value = await api.usage()
  } catch (e) {
    // 拿不到不影响使用，静默忽略即可
  }
}

// ==================== 会话操作 ====================

/** 点侧边栏某条会话：把它的历史消息拉回来显示 */
async function openConversation(id) {
  if (id === currentId.value || loadingConv.value) return
  loadingConv.value = true
  try {
    const conv = await api.conversation(id)
    currentId.value = conv.id
    messages.value = (conv.messages || []).map((m) => ({
      role: m.role,
      text: m.text,
      sources: m.sources || [],
      // mode 决定这条回答显示"基于文档"还是"普通对话"标签（旧数据后端已兜底成 rag）
      mode: m.mode || 'rag'
    }))
    await scrollToBottom()
  } catch (e) {
    if (e.status === 404) {
      notify('这个会话已经不存在了，列表已刷新')
      await fetchConversations()
    } else {
      notify('加载会话失败：' + e.message)
    }
  } finally {
    loadingConv.value = false
  }
}

/**
 * 新对话
 *
 * 注意这里**没有**请求后端新建会话——这是刻意的。
 * 如果一点"新对话"就让后端建一条，点十次就留下十条空记录，列表很快就废了。
 * 真正的创建发生在用户问出第一句话时（后端 ensure 逻辑），那时才有意义。
 */
function newConversation() {
  currentId.value = ''
  messages.value = []
  input.value = ''
  nextTick(() => inputEl.value?.focus())
}

/** 删除会话 */
async function removeConversation(conv) {
  const label = conv.title || '这个会话'
  if (!window.confirm(`确定删除「${label}」吗？删除后无法恢复。`)) {
    return
  }
  try {
    await api.remove(conv.id)
    if (conv.id === currentId.value) {
      currentId.value = ''
      messages.value = []
    }
    await fetchConversations()
    notify('已删除')
  } catch (e) {
    notify('删除失败：' + e.message)
  }
}

/** 重命名会话 */
async function renameConversation(id, title) {
  try {
    await api.rename(id, title)
    await fetchConversations()
  } catch (e) {
    notify('重命名失败：' + e.message)
  }
}

/** 加载文档建立索引 */
async function loadDocs() {
  if (loadingDocs.value) return
  loadingDocs.value = true
  notify('正在加载文档并建立索引，请稍候…', 6000)
  try {
    const data = await api.load()
    await fetchStatus()
    notify(data.message, 8000)
  } catch (e) {
    // e.message 已经是后端 GlobalExceptionHandler 翻译好的中文提示
    notify('加载失败：' + e.message, 8000)
  } finally {
    loadingDocs.value = false
  }
}

// ==================== 提问 ====================

async function send(preset) {
  const question = (typeof preset === 'string' ? preset : input.value).trim()
  if (!question || loading.value) return

  // 1. 先把用户这句话显示出来
  messages.value.push({ role: 'user', text: question, sources: [] })
  input.value = ''
  await scrollToBottom()

  // 2. 插一条"正在思考"的占位消息
  //    pending   = 还没收到第一个字（显示思考动画）
  //    streaming = 正在逐字输出（显示打字光标）
  messages.value.push({
    role: 'assistant',
    text: '',
    sources: [],
    pending: true,
    streaming: true
  })
  const replyIndex = messages.value.length - 1
  loading.value = true
  await scrollToBottom()

  // 每个字都滚一次底会太频繁，做个节流：80 毫秒最多滚一次
  let lastScrollAt = 0

  try {
    // conversationId 是这块的关键：带上它，后端就知道这是同一个会话，能记住上文
    await api.chatStream(currentId.value, question, {
      // 元信息最先到：会话 ID、引用来源有了，正文还在路上
      onMeta: (meta) => {
        // 下一次提问带上 conversationId 就能"接着聊"
        if (meta.conversationId) currentId.value = meta.conversationId

        // 注意：要通过 messages.value[replyIndex] 去改（拿到的是响应式代理），
        // 直接改占位对象是改不到页面上的
        Object.assign(messages.value[replyIndex], {
          sources: meta.sources || [],
          // 后端会告诉我们这次走的是 RAG 还是普通对话，前端据此显示标签
          mode: meta.mode || 'rag',
          pending: false
        })
      },

      // 每来一小段正文就往消息里追加，页面上自然就是打字机效果
      onToken: (delta) => {
        messages.value[replyIndex].text += delta
        const now = Date.now()
        if (now - lastScrollAt > 80) {
          lastScrollAt = now
          scrollToBottom()
        }
      },

      /**
       * 引用来源（Tool Use 模式才有）
       *
       * 模型是「先调用知识库工具、拿到结果之后才开始回答」，所以引用来源
       * 在 onMeta 那一刻还不存在，这个事件是后补送来的。
       * 一旦收到，就把标签从"普通对话"升级成"基于 Spring 文档"——
       * 因为它确实查了知识库。
       */
      onSources: (list) => {
        if (!list || !list.length) return
        Object.assign(messages.value[replyIndex], {
          sources: list,
          mode: 'rag'
        })
      },

      onError: (msg) => {
        // SSE 通道里的错误不走后端的异常处理器（响应已经开始推流了），
        // 所以这里自己判断最常见的两种：限速和 Key 失效
        const text = String(msg)
        let friendly
        if (/429|速率限制|限速|请求过于频繁|频率|rate.?limit|too many/i.test(text)) {
          friendly = '**请求太密集了**\n\n免费模型都有速率上限（用得越勤越容易撞上）。' +
            '**等 10~20 秒再问一次就好**，已建好的索引不受影响。\n\n' +
            '如果频繁出现，可以在 `application.yaml` 里把 `doc-agent.embedding.qps` 调小（比如 2）。'
        } else if (/401|Token is invalid|令牌|余额不足|unauthorized/i.test(text)) {
          friendly = '**API Key 不可用**\n\n请检查 IDEA 运行配置里的环境变量：' +
            '`ZHIPU_API_KEY`（智谱对话）和 `SILICONFLOW_API_KEY`（硅基流动向量）。'
        } else {
          friendly = '**生成失败：** ' + text
        }
        Object.assign(messages.value[replyIndex], {
          text: messages.value[replyIndex].text || friendly,
          pending: false
        })
      }
    })
  } catch (e) {
    // 后端已经把异常翻译成中文了（限速/Key 失效/数据库没开各有各的提示），
    // 这里只补一条"通用自检清单"，不再自己拼错误文案
    Object.assign(messages.value[replyIndex], {
      text:
        '**请求失败**\n\n' +
        e.message +
        '\n\n---\n\n如果上面没给出具体原因，按这个顺序排查：\n\n' +
        '1. IDEA 里 `DocAgentApplication` 是否在运行（8081 端口）\n' +
        '2. 左侧知识库状态是否显示片段数（0 说明还没建索引）\n' +
        '3. 环境变量 `DASHSCOPE_API_KEY`（百炼对话）/ `SILICONFLOW_API_KEY`（硅基流动向量）是否都配了，且改完重新 Run 过',
      pending: false
    })
  } finally {
    // 不管成功失败都要收尾：去掉打字光标和思考动画、解锁输入框
    Object.assign(messages.value[replyIndex], { pending: false, streaming: false })
    loading.value = false
    await scrollToBottom()
    // 顺便刷新 token 计数，让页脚那行数字跟着更新
    fetchUsage()
    // 刷新侧边栏：新会话要出现在列表里，标题也要从"新对话"变成这句话
    await fetchConversations()
    inputEl.value?.focus()
  }
}

// ==================== 生命周期 ====================
onMounted(async () => {
  await Promise.all([fetchStatus(), fetchConversations(), fetchUsage()])
  inputEl.value?.focus()
})
</script>

<template>
  <div class="app">
    <Sidebar
      :conversations="conversations"
      :current-id="currentId"
      :status="status"
      :loading-docs="loadingDocs"
      :busy="loading"
      @create="newConversation"
      @select="openConversation"
      @remove="removeConversation"
      @rename="renameConversation"
      @load="loadDocs"
    />

    <main class="main">
      <!-- 顶栏 -->
      <header class="topbar">
        <div class="topbar-title">{{ currentTitle }}</div>
      </header>

      <!-- 消息区 -->
      <div ref="listEl" class="messages">
        <div v-if="loadingConv" class="loading-conv">正在加载会话…</div>

        <div v-else-if="!messages.length" class="empty">
          <div class="empty-logo">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.2">
              <path d="M12 3l7.5 4.4v8.9L12 21l-7.5-4.7V7.4z" stroke-linejoin="round" />
              <path d="M12 12l7.3-4.3M12 12v8.6M12 12L4.7 7.7" stroke-linejoin="round" />
            </svg>
          </div>
          <h2>问一个 Spring 相关的问题</h2>
          <p>技术问题我会翻遍 Spring 官方文档、基于相关段落回答并标出出处；打招呼闲聊就正常聊</p>

          <div class="examples">
            <button v-for="q in examples" :key="q" @click="send(q)">
              <span class="example-text">{{ q }}</span>
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
                <path d="M6 3.5L10.5 8 6 12.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>

          <p v-if="!status.ready" class="hint">
            知识库还是空的，先在左下角点「加载文档」建立索引，再问技术问题（闲聊可以直接聊）
          </p>
        </div>

        <template v-else>
          <ChatMessage v-for="(msg, i) in messages" :key="i" :message="msg" />
        </template>
      </div>

      <!-- 输入区 -->
      <footer class="composer">
        <div class="composer-box">
          <textarea
            ref="inputEl"
            v-model="input"
            rows="1"
            placeholder="输入问题，Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="send()"
          ></textarea>
          <button class="send" :disabled="loading || !input.trim()" @click="send()">
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M3 8h10M9 4l4 4-4 4" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
        </div>
        <p class="composer-tip">
          技术问题自动检索文档作答并附引用，闲聊自动切换普通对话 · 重要配置请以官方为准
        </p>
        <p v-if="usage && usage.totalTokens > 0" class="usage-tip">
          本次运行已用 {{ usage.totalTokens.toLocaleString() }} tokens
          （输入 {{ usage.promptTokens.toLocaleString() }} / 输出 {{ usage.completionTokens.toLocaleString() }}，
          共 {{ usage.requestCount }} 次调用）
        </p>
      </footer>

      <!-- 轻提示 -->
      <transition name="toast">
        <div v-if="toast" class="toast">{{ toast }}</div>
      </transition>
    </main>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  height: 100%;
  overflow: hidden;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  position: relative;
}

/* ===== 顶栏 ===== */
.topbar {
  flex-shrink: 0;
  height: 52px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid var(--border);
}

.topbar-title {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ===== 消息区 ===== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 8px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.messages > * {
  max-width: 820px;
  width: 100%;
  margin: 0 auto;
}

.loading-conv {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
  padding: 40px 0;
}

/* ===== 空状态 ===== */
.empty {
  margin: auto;
  text-align: center;
  padding: 20px 0;
}

.empty-logo {
  width: 52px;
  height: 52px;
  margin: 0 auto 18px;
  border-radius: 14px;
  background: var(--primary-soft);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-logo svg {
  width: 28px;
  height: 28px;
}

.empty h2 {
  font-size: 18px;
  font-weight: 500;
  margin: 0 0 8px;
}

.empty > p {
  color: var(--text-secondary);
  margin: 0 0 24px;
  font-size: 13px;
}

.examples {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 420px;
  margin: 0 auto;
}

.examples button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  text-align: left;
  padding: 11px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
  transition: all 0.15s;
}

.examples button:hover {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary);
}

.examples button svg {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  opacity: 0.5;
}

.example-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint {
  margin-top: 22px !important;
  font-size: 12px;
  color: #99600a;
  background: #fdf6e7;
  border: 1px solid #f0dfb8;
  border-radius: 8px;
  padding: 9px 12px;
  display: inline-block;
}

/* ===== 输入区 ===== */
.composer {
  flex-shrink: 0;
  padding: 12px 24px 16px;
  max-width: 868px;
  width: 100%;
  margin: 0 auto;
}

.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  padding: 8px 8px 8px 14px;
  background: var(--surface);
  transition: border-color 0.15s;
}

.composer-box:focus-within {
  border-color: var(--primary);
}

.composer textarea {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  background: transparent;
  max-height: 160px;
  min-height: 24px;
  padding: 4px 0;
}

.send {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 9px;
  background: var(--primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.send:hover:not(:disabled) {
  background: var(--primary-hover);
}

.send svg {
  width: 16px;
  height: 16px;
}

.composer-tip {
  margin: 7px 0 0;
  text-align: center;
  font-size: 11px;
  color: var(--text-tertiary);
}

/* token 用量：比 tip 再淡一点，避免和主提示抢注意力 */
.usage-tip {
  margin: 3px 0 0;
  text-align: center;
  font-size: 11px;
  color: var(--text-tertiary);
  opacity: 0.75;
  font-variant-numeric: tabular-nums;
}

/* ===== 轻提示 ===== */
.toast {
  position: absolute;
  top: 64px;
  left: 50%;
  transform: translateX(-50%);
  max-width: 560px;
  background: #2c2f33;
  color: #fff;
  font-size: 12.5px;
  line-height: 1.6;
  padding: 9px 16px;
  border-radius: 9px;
  z-index: 20;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-6px);
}
</style>
