import MarkdownIt from 'markdown-it'
// 按需引入语言包：全量引入 highlight.js 会让打包体积暴涨（900KB+），
// 我们只需要 Spring 文档里会出现的那几种
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import properties from 'highlight.js/lib/languages/properties'
import bash from 'highlight.js/lib/languages/bash'
import sql from 'highlight.js/lib/languages/sql'
import json from 'highlight.js/lib/languages/json'
import groovy from 'highlight.js/lib/languages/groovy'

hljs.registerLanguage('java', java)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('yml', yaml)
hljs.registerLanguage('properties', properties)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('json', json)
hljs.registerLanguage('groovy', groovy)

/**
 * Markdown 渲染器
 *
 * 为什么需要它？
 * 大模型返回的是 Markdown 源码（"### 配置方法"、``` 代码块），
 * 浏览器不认识这些记号，直接显示就会看到一堆 # 和反引号。
 * 这一步就是把它翻译成真正的 <h3>、<pre> 标签。
 *
 * 安全说明：html: false 表示不解析原始 HTML——
 * 模型如果返回 <script> 之类的标签，会被当成纯文本显示，不会被执行。
 * 这是防止提示词注入的关键开关，别关掉。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch {
        // 高亮失败就退回纯文本，不影响阅读
      }
    }
    return ''
  }
})

// 让外链在新标签打开，避免用户点走后丢掉当前对话
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  function (tokens, idx, options, env, self) {
    return self.renderToken(tokens, idx, options)
  }
md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text) {
  if (!text) return ''
  return md.render(text)
}

/**
 * 把爬虫存下来的文件名美化成可读标题
 *
 * 004-features-ssl.md → Features Ssl
 * 只在文档没有自带标题时作为兜底使用。
 */
export function prettyFileName(file) {
  if (!file) return '未知来源'
  return file
    .replace(/\.(md|txt)$/i, '')
    .replace(/^\d+[-_]/, '')
    .split(/[-_]/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}
