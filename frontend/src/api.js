/**
 * 后端接口封装
 *
 * 为什么单独抽一个文件？
 * 一是把 fetch 的重复代码收拢到一处；二是以后改接口地址（比如上线后不再是 /api），
 * 只改这里就行，不用满项目找 fetch。
 */

async function request(url, options = {}) {
  const res = await fetch(url, options)

  if (!res.ok) {
    // 404 单独处理：说明这个会话已经被删了
    if (res.status === 404) {
      const err = new Error('NOT_FOUND')
      err.status = 404
      throw err
    }
    // 后端 GlobalExceptionHandler 会返回 { message: "人话解释" }，
    // 把它取出来带上——不然这里只能 throw new Error('HTTP 500')，用户啥也看不懂
    const err = new Error(await readErrorDetail(res))
    err.status = res.status
    throw err
  }

  const type = res.headers.get('content-type') || ''
  if (type.includes('application/json')) {
    return res.json()
  }
  // 空响应体（比如 DELETE 成功）走这里
  return res.text()
}

/** 从错误响应里挖出后端给的中文提示，挖不到就退回状态码 */
async function readErrorDetail(res) {
  try {
    const text = await res.text()
    const data = JSON.parse(text)
    return data.message || data.error || ('HTTP ' + res.status)
  } catch {
    return 'HTTP ' + res.status
  }
}

/**
 * 提问（流式版）—— 模型想到哪儿，这里就收到哪儿
 *
 * 为什么不用浏览器自带的 EventSource？
 * EventSource 只能发 GET 请求，而我们要把问题放在 POST 的请求体里。
 * 所以用 fetch 手动读流：拿到响应体的 reader，边收边解析。
 *
 * SSE 的报文长这样（每个事件以空行结尾）：
 *   event: token
 *   data: "一小段文字"
 *
 *   event: sources        ← Tool Use 模式才有
 *   data: [{file,title,score,snippet}, ...]
 *
 *   event: done
 *   data: "ok"
 *
 * @param handlers.onMeta    收到会话 ID / 模式时回调（一次）
 * @param handlers.onToken   收到一小段正文时回调（很多次）
 * @param handlers.onSources 收到引用来源时回调（0~1 次，模型调用知识库工具后才会有）
 * @param handlers.onError   出错时回调
 * @param handlers.onDone    正常结束时回调
 */
export async function streamChat(conversationId, question, handlers = {}) {
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, question })
  })

  if (!res.ok) throw new Error(await readErrorDetail(res))

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // 一个事件以空行结束，把 buffer 里已经完整的事件逐个切出来处理
    let sep
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      handleEvent(raw, handlers)
    }
  }

  // 收尾时 buffer 里可能还剩一个没带末尾空行的事件
  if (buffer.trim()) handleEvent(buffer, handlers)
}

/** 解析单个 SSE 事件并分发给对应回调 */
function handleEvent(raw, handlers) {
  let name = 'message'
  const dataLines = []

  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // 去掉 "data:" 以及紧随其后的一个空格（协议允许带一个空格）
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }

  if (!dataLines.length) return

  // 后端所有 data 都做过 JSON 编码，所以这里一定能解析
  let payload
  try {
    payload = JSON.parse(dataLines.join('\n'))
  } catch {
    return
  }

  if (name === 'meta' && handlers.onMeta) handlers.onMeta(payload)
  else if (name === 'token' && handlers.onToken) handlers.onToken(payload)
  else if (name === 'sources' && handlers.onSources) handlers.onSources(payload)
  // ReAct 循环的进度提示（可选事件，只有走 ReAct 的问题才有）
  else if (name === 'step' && handlers.onStep) handlers.onStep(payload)
  else if (name === 'error' && handlers.onError) handlers.onError(payload)
  else if (name === 'done' && handlers.onDone) handlers.onDone()
}

export const api = {
  /** 知识库状态 */
  status: () => request('/api/status'),

  /** token 用量（本次运行累计） */
  usage: () => request('/api/usage'),

  /** 加载文档建立索引 */
  load: () => request('/api/load', { method: 'POST' }),

  /** 提问（一次性返回） */
  chat: (conversationId, question) =>
    request('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, question })
    }),

  /** 提问（流式，逐字返回） */
  chatStream: (conversationId, question, handlers) => streamChat(conversationId, question, handlers),

  /** 会话列表（摘要） */
  conversations: () => request('/api/conversations'),

  /** 某个会话的完整内容 */
  conversation: (id) => request('/api/conversations/' + encodeURIComponent(id)),

  /** 重命名 */
  rename: (id, title) =>
    request('/api/conversations/' + encodeURIComponent(id), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title })
    }),

  /** 删除 */
  remove: (id) =>
    request('/api/conversations/' + encodeURIComponent(id), { method: 'DELETE' })
}
