<template>
  <div class="version-log-container">
    <header class="page-header">
      <h2>文档版本历史</h2>
      <p class="subtitle">查询文档的入库版本记录及对应权限事件溯源链路</p>
    </header>

    <!-- 搜索区 -->
    <section class="search-section">
      <h3 class="section-title">版本查询</h3>
      <div class="search-row">
        <input
          type="text"
          v-model="searchName"
          placeholder="输入文档名称（如：政法规程.docx）进行精确查询"
          class="search-input"
          @keyup.enter="fetchVersions"
        />
        <button class="btn-primary" @click="fetchVersions" :disabled="loading">
          {{ loading ? '查询中...' : '🔍 查询版本' }}
        </button>
      </div>
    </section>

    <!-- 版本列表 -->
    <section class="list-section" v-if="searched">
      <h3 class="section-title">
        版本列表
        <span class="count-badge">共 {{ versions.length }} 个版本</span>
      </h3>

      <div v-if="versions.length === 0" class="empty-state">
        <div class="empty-icon">📂</div>
        <p>未找到「{{ searchName }}」的版本记录</p>
        <span class="empty-hint">请确认文档名称与入库时一致（含扩展名）</span>
      </div>

      <div v-else class="version-list">
        <div
          v-for="v in versions"
          :key="v.id"
          class="version-card"
          :class="{ 'is-latest': v.docVersion === maxVersion }"
        >
          <!-- 版本主信息行 -->
          <div class="version-header" @click="toggleEvents(v)">
            <div class="version-badge-group">
              <span class="version-tag" :class="v.docVersion === maxVersion ? 'latest' : 'old'">
                v{{ v.docVersion }}{{ v.docVersion === maxVersion ? '（当前）' : '' }}
              </span>
              <span class="vis-tag" :class="(v.visibility || 'INTERNAL').toLowerCase()">
                {{ v.visibility || 'INTERNAL' }}
              </span>
            </div>
            <div class="version-meta">
              <span class="meta-item" title="内容哈希">🔑 {{ v.contentHash ? v.contentHash.substring(0, 8) + '...' : '--' }}</span>
              <span class="meta-item">📄 {{ v.chunkCount || 0 }} 个切片</span>
              <span class="meta-item">👤 {{ v.operatorId || 'system' }}</span>
              <span class="meta-item">🕐 {{ formatDate(v.createdAt) }}</span>
            </div>
            <div class="expand-icon" :class="{ rotated: expandedVersionId === v.id }">
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>
            </div>
          </div>

          <!-- 展开：权限事件列表 -->
          <div class="events-panel" v-if="expandedVersionId === v.id">
            <div v-if="eventsLoading" class="events-loading">加载权限事件链路中...</div>
            <div v-else-if="currentEvents.length === 0" class="events-empty">该版本暂无权限事件记录</div>
            <div v-else class="events-timeline">
              <div
                v-for="evt in currentEvents"
                :key="evt.id"
                class="event-item"
                :class="evt.action.toLowerCase()"
              >
                <div class="event-dot"></div>
                <div class="event-content">
                  <div class="event-header">
                    <span class="event-action">{{ actionLabel(evt.action) }}</span>
                    <span class="event-type">{{ evt.targetType }}: {{ evt.targetValue }}</span>
                    <span class="event-operator">操作人: {{ evt.operatorId }}</span>
                  </div>
                  <div v-if="evt.remark" class="event-remark">{{ evt.remark }}</div>
                  <div class="event-time">{{ formatDate(evt.createdAt) }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1';
// DocPermissionController 挂载于 /api/doc/perm，与 API_BASE 无关，直接使用后端根路径
const PERM_BASE = API_BASE.replace('/api/v1', '/api/doc/perm');

const searchName  = ref('');
const versions    = ref([]);
const loading     = ref(false);
const searched    = ref(false);

const expandedVersionId = ref(null);
const currentEvents     = ref([]);
const eventsLoading     = ref(false);

// 当前最大版本号（用于标注「当前版本」角标）
const maxVersion = computed(() =>
  versions.value.reduce((max, v) => Math.max(max, v.docVersion || 0), 0)
);

/**
 * 查询指定文档的版本历史
 * 调用：GET /api/doc/perm/versions/{source}
 */
const fetchVersions = async () => {
  if (!searchName.value.trim()) return;
  loading.value = true;
  searched.value = true;
  expandedVersionId.value = null;
  currentEvents.value = [];
  try {
    const name = encodeURIComponent(searchName.value.trim());
    const res  = await axios.get(`${PERM_BASE}/versions/${name}`);
    versions.value = Array.isArray(res.data) ? res.data : [];
  } catch (err) {
    console.error('版本查询失败', err);
    versions.value = [];
  } finally {
    loading.value = false;
  }
};

/**
 * 展开 / 收起某版本的权限事件，展开时加载事件链路
 * 调用：GET /api/doc/perm/events/{docId}
 */
const toggleEvents = async (v) => {
  // 折叠：点击同一个已展开的版本
  if (expandedVersionId.value === v.id) {
    expandedVersionId.value = null;
    return;
  }
  expandedVersionId.value = v.id;
  eventsLoading.value = true;
  currentEvents.value = [];
  try {
    const docId = encodeURIComponent(searchName.value.trim());
    const res   = await axios.get(`${PERM_BASE}/events/${docId}`);
    currentEvents.value = Array.isArray(res.data) ? res.data : [];
  } catch (err) {
    console.error('权限事件加载失败', err);
    currentEvents.value = [];
  } finally {
    eventsLoading.value = false;
  }
};

/** 权限动作枚举的中文标签 */
const actionLabel = (action) => {
  const map = {
    GRANT:            '授权',
    REVOKE:           '撤权',
    HANDLER:          '经手',
    VISIBILITY_CHANGE:'可见度变更',
  };
  return map[action] || action;
};

const formatDate = (str) => {
  if (!str) return '--';
  return new Date(str).toLocaleString('zh-CN', { hour12: false });
};
</script>

<style scoped>
.version-log-container {
  max-width: 1100px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 40px;
  animation: fadeIn 0.6s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}

.page-header h2 {
  font-size: 32px;
  font-weight: 800;
  color: var(--text-main);
  margin: 0 0 10px;
  letter-spacing: -1px;
}
.subtitle {
  color: var(--text-muted);
  font-size: 15px;
}

.search-section, .list-section {
  background: var(--snow-bg);
  padding: 40px;
  border-radius: 24px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
}

.section-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--accent-blue);
  margin-bottom: 28px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border-ultra-light);
  display: flex;
  align-items: center;
  gap: 12px;
}
.section-title::before {
  content: '';
  width: 4px;
  height: 18px;
  background: var(--accent-blue);
  border-radius: 2px;
}

.count-badge {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  background: var(--snow-soft);
  padding: 3px 10px;
  border-radius: 20px;
  border: 1px solid var(--border-ultra-light);
}

.search-row {
  display: flex;
  gap: 16px;
}
.search-input {
  flex: 1;
  padding: 14px 20px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
}
.search-input:focus {
  border-color: var(--accent-blue);
  background: var(--snow-bg);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.btn-primary {
  background: var(--accent-blue);
  color: white;
  padding: 14px 28px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
  transition: all 0.25s;
}
.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.3);
}
.btn-primary:disabled {
  background: var(--border-light);
  color: var(--text-muted);
  box-shadow: none;
  cursor: not-allowed;
}

/* 空状态 */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted);
}
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state p { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.empty-hint  { font-size: 13px; opacity: 0.7; }

/* 版本卡片列表 */
.version-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.version-card {
  border: 1px solid var(--border-ultra-light);
  border-radius: 16px;
  overflow: hidden;
  transition: box-shadow 0.2s;
}
.version-card:hover {
  box-shadow: var(--shadow-sm);
}
.version-card.is-latest {
  border-color: rgba(37, 99, 235, 0.25);
}

.version-header {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px 24px;
  cursor: pointer;
  background: var(--snow-soft);
  transition: background 0.15s;
}
.version-header:hover {
  background: var(--snow-bg);
}

.version-badge-group {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.version-tag {
  padding: 5px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 800;
}
.version-tag.latest { background: var(--accent-soft); color: var(--accent-blue); }
.version-tag.old    { background: var(--snow-bg); color: var(--text-muted); border: 1px solid var(--border-light); }

.vis-tag {
  padding: 5px 10px;
  border-radius: 8px;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}
.vis-tag.public   { background: #DCFCE7; color: #16A34A; }
.vis-tag.internal { background: #EFF6FF; color: #2563EB; }
.vis-tag.dept     { background: #FEF3C7; color: #D97706; }
.vis-tag.private  { background: #FEE2E2; color: #DC2626; }
.vis-tag.grant    { background: #F3E8FF; color: #7C3AED; }

.version-meta {
  display: flex;
  gap: 20px;
  flex: 1;
  flex-wrap: wrap;
}
.meta-item {
  font-size: 13px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}

.expand-icon {
  flex-shrink: 0;
  color: var(--text-muted);
  transition: transform 0.25s;
}
.expand-icon.rotated { transform: rotate(180deg); }

/* 权限事件时间轴 */
.events-panel {
  padding: 24px 28px;
  border-top: 1px solid var(--border-ultra-light);
  background: var(--snow-bg);
}
.events-loading, .events-empty {
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
  padding: 20px;
}

.events-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
  border-left: 2px solid var(--border-light);
  margin-left: 10px;
  padding-left: 24px;
}

.event-item {
  position: relative;
  padding: 10px 0;
  display: flex;
  gap: 0;
}

.event-dot {
  position: absolute;
  left: -33px;
  top: 16px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid white;
  background: var(--border-light);
}
.event-item.handler .event-dot        { background: #60A5FA; }
.event-item.grant .event-dot          { background: #34D399; }
.event-item.revoke .event-dot         { background: #F87171; }
.event-item.visibility_change .event-dot { background: #A78BFA; }

.event-content { flex: 1; }

.event-header {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.event-action {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-main);
}
.event-type, .event-operator {
  font-size: 13px;
  color: var(--text-muted);
}
.event-remark {
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
  margin-bottom: 4px;
}
.event-time {
  font-size: 12px;
  color: var(--border-light);
}
</style>
