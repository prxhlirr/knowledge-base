<template>
  <div class="admin-container">
    <aside class="admin-sidebar">
      <div class="brand-box">
        <h2 class="brand-title">中台管控</h2>
      </div>
      
      <nav class="admin-nav">
        <router-link to="/admin" class="nav-link" active-class="active" exact>数据总览</router-link>
        <router-link to="/admin/tuning" class="nav-link" active-class="active">模型调优</router-link>
        <router-link to="/admin/synonyms" class="nav-link" active-class="active">同义词词典</router-link>
        <router-link to="/admin/doc-import" class="nav-link" active-class="active">文档接入</router-link>
        <router-link to="/admin/similarity" class="nav-link" active-class="active" style="color: var(--accent-blue); font-weight: 600;">相似度探针</router-link>
        <router-link to="/admin/tags" class="nav-link" active-class="active">打标管理</router-link>
        <router-link to="/admin/logs" class="nav-link" active-class="active">解析日志</router-link>
        <router-link to="/admin/version-log" class="nav-link" active-class="active">版本历史</router-link>
        <router-link to="/admin/doc-management" class="nav-link" active-class="active">文档管理</router-link>
        <a href="#" class="nav-link">权限配置</a>
        <router-link to="/" class="nav-link back-link">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-arrow-left"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          返回搜索
        </router-link>
      </nav>
    </aside>

    <main class="admin-content">
      <header class="content-header">
        <h1>概览仪表盘</h1>
        <div class="user-profile">管理员 (Admin)</div>
      </header>

      <div v-if="$route.name === 'Admin'" class="dashboard-home">
        <div class="stats-grid">
          <div class="stat-card">
            <span class="stat-label">索引文档总数</span>
            <span class="stat-value">---</span>
            <span class="stat-trend up">ES Cluster: Healthy</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">AI 引擎负载</span>
            <span class="stat-value">CPU / FP16</span>
            <span class="stat-trend">BGE-m3 Model Loaded</span>
          </div>
          <div class="stat-card">
            <span class="stat-label">今日搜索次数</span>
            <span class="stat-value">64</span>
            <span class="stat-trend">RRF Hybrid Search</span>
          </div>
        </div>

        <div class="recent-docs">
          <h3>最近入库队列</h3>
          <div class="table-placeholder">
            此处将展示最近的文档解析、分片与向量化进度任务...
          </div>
        </div>
      </div>

      <!-- 子路由渲染区 (模型调优等) -->
      <router-view v-else></router-view>
    </main>
  </div>
</template>

<style scoped>
.admin-container {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background-color: var(--snow-soft);
}

.admin-sidebar {
  width: 260px;
  display: flex;
  flex-direction: column;
  z-index: 10;
  /* 应用全局玻璃面板效果 */
  background: var(--glass-white);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border-right: 1px solid var(--border-ultra-light);
}

.brand-box {
  padding: 40px 32px;
}

.brand-title {
  font-size: 20px;
  margin: 0;
  color: var(--accent-blue);
  font-weight: 800;
  letter-spacing: -0.5px;
}

.admin-nav {
  flex: 1;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-link {
  padding: 14px 20px;
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 500;
  border-radius: 12px;
  transition: var(--transition-smooth);
}

.nav-link:hover {
  background: rgba(0, 0, 0, 0.03);
  color: var(--text-main);
  transform: translateX(4px);
}

.nav-link.active {
  background: var(--accent-soft);
  color: var(--accent-blue);
  font-weight: 600;
}

.back-link {
  margin-top: auto;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--snow-bg, #ffffff);
  border: 1px solid var(--border-ultra-light, #e5e7eb);
  color: var(--text-main, #111827);
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.back-link:hover {
  background-color: var(--accent-blue, #1A56DB);
  color: white;
  border-color: var(--accent-blue, #1A56DB);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(26, 86, 219, 0.15);
}

.back-link svg {
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.back-link:hover svg {
  transform: translateX(-3px);
}

.admin-content {
  flex: 1;
  padding: 48px 64px;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 48px;
}

.content-header h1 {
  font-size: 28px;
  font-weight: 700;
  margin: 0;
  letter-spacing: -0.8px;
}

.user-profile {
  font-size: 14px;
  color: var(--text-muted);
  background: var(--snow-bg);
  padding: 8px 16px;
  border-radius: 20px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-sm);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 32px;
  margin-bottom: 56px;
}

/* 赋予统计卡片 minimal-card 属性 */
.stat-card {
  background: var(--snow-bg);
  padding: 32px;
  border-radius: 20px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: var(--transition-smooth);
}

.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg);
}

.stat-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.stat-value {
  font-size: 32px;
  font-weight: 800;
  color: var(--text-main);
}

.stat-trend {
  font-size: 12px;
  color: #10B981;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 4px;
}

.recent-docs {
  background: var(--snow-bg);
  padding: 40px;
  border-radius: 24px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
  min-height: 360px;
}

.recent-docs h3 {
  font-size: 18px;
  font-weight: 700;
  margin-bottom: 32px;
}

.table-placeholder {
  color: var(--text-muted);
  font-size: 15px;
  text-align: center;
  margin-top: 100px;
  opacity: 0.6;
}
</style>
