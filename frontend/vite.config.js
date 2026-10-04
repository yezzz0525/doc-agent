import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 代理：前端代码里写 fetch('/api/chat')，Vite 会自动转发到后端 8081。
    // 这样前端不用关心后端地址，也不会遇到跨域问题（浏览器看到的是同一个域）。
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
