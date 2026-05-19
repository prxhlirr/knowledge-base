import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'chrome100' // 显式匹配用户的基准游览器诉求
  },
  server: {
    // 开发环境代理：将所有 /api 请求透传到后端服务
    // 避免前端(5173)直接跨域请求后端(8080)触发浏览器 CORS 限制
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    },
    host: '0.0.0.0'
  }
})
