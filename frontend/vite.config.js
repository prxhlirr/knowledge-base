import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import http from 'node:http'

const apiProxyAgent = new http.Agent({
  keepAlive: false
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'chrome100', // 显式匹配用户的基准游览器诉求
    rollupOptions: {
      input: {
        app: 'index.html'
      }
    }
  },
  server: {
    // 开发环境代理：将所有 /api 请求透传到后端服务
    // 避免前端(5173)直接跨域请求后端(8080)触发浏览器 CORS 限制
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        secure: false,
        timeout: 120000,
        proxyTimeout: 120000,
        agent: apiProxyAgent,
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('Connection', 'close')
          })
          proxy.on('error', (err, req) => {
            console.error(`[vite-proxy] ${req.method} ${req.url}: ${err.message}`)
          })
        }
      }
    },
    host: '0.0.0.0'
  }
})
