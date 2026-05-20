<template>
  <div class="prompt-container">
    <header class="page-header">
      <div>
        <h2>Prompt 模板管理</h2>
        <p class="subtitle">
          管理所有 LLM 调用场景的 Prompt，修改后 AI 服务将在
          <strong>60 秒内</strong>自动生效，无需重启。
        </p>
      </div>
      <div class="header-actions">
        <div class="scene-filter">
          <button
            v-for="s in ['全部', ...scenes]"
            :key="s"
            class="scene-tab"
            :class="{ active: activeScene === s }"
            @click="activeScene = s"
          >{{ s }}</button>
        </div>
      </div>
    </header>

    <!-- 加载态 -->
    <div v-if="loading" class="loading-state">正在加载 Prompt 模板...</div>

    <!-- 按 scene 分组的卡片列表 -->
    <div v-else class="scene-list">
      <div
        v-for="(group, scene) in filteredGroups"
        :key="scene"
        class="scene-card"
      >
        <!-- 场景标题行 -->
        <div class="scene-header" @click="toggleCollapse(scene)">
          <div class="scene-title">
            <span class="scene-badge">{{ scene }}</span>
            <span class="scene-desc">{{ SCENE_DESC[scene] || '' }}</span>
          </div>
          <div class="scene-meta">
            <span class="count-tag">{{ group.length }} 条</span>
            <span class="collapse-icon">{{ collapsed[scene] ? '▶' : '▼' }}</span>
          </div>
        </div>

        <!-- 场景内的 Prompt 条目 -->
        <div v-show="!collapsed[scene]" class="prompt-list">
          <div
            v-for="item in group"
            :key="item.promptKey"
            class="prompt-item"
            :class="{ 'item-disabled': item.isActive === 0 }"
          >
            <div class="prompt-item-left">
              <div class="prompt-meta-row">
                <span class="role-badge" :class="item.role">{{ item.role }}</span>
                <code class="prompt-key">{{ item.promptKey }}</code>
                <span class="status-pill" :class="item.isActive === 1 ? 'active' : 'inactive'">
                  {{ item.isActive === 1 ? '✓ 启用' : '○ 停用' }}
                </span>
                <span class="version-tag">v{{ item.version }}</span>
              </div>
              <!-- Prompt 正文预览（最多显示 3 行） -->
              <pre class="prompt-preview">{{ item.content }}</pre>
              <p v-if="item.description" class="prompt-desc">{{ item.description }}</p>
            </div>

            <div class="prompt-item-actions">
              <button
                class="btn-edit"
                @click="openEdit(item)"
                :id="'btn-edit-' + item.promptKey"
              >编辑</button>
              <button
                class="btn-toggle"
                @click="toggleActive(item)"
                :id="'btn-toggle-' + item.promptKey"
              >{{ item.isActive === 1 ? '停用' : '启用' }}</button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="Object.keys(filteredGroups).length === 0" class="empty-tip">
        暂无 Prompt 数据，请先执行 <code>sql/add_prompt_template.sql</code> 初始化表数据。
      </div>
    </div>

    <!-- 编辑 Modal -->
    <div v-if="editingItem" class="modal-overlay" @click.self="closeEdit">
      <div class="modal-card">
        <div class="modal-header">
          <div>
            <h3 class="modal-title">编辑 Prompt</h3>
            <p class="modal-subtitle">
              <span class="role-badge" :class="editingItem.role">{{ editingItem.role }}</span>
              <code class="prompt-key">{{ editingItem.promptKey }}</code>
            </p>
          </div>
        </div>

        <!-- 占位符说明 -->
        <div class="placeholder-hint">
          <span class="hint-title">📌 可用占位符：</span>
          <code v-for="ph in PLACEHOLDER_HINT[editingItem.scene] || ['{query}']" :key="ph" class="ph-chip">{{ ph }}</code>
        </div>

        <!-- Prompt 正文编辑 -->
        <div class="form-item">
          <label>Prompt 正文 <span class="required">*</span></label>
          <textarea
            v-model="editForm.content"
            rows="10"
            class="prompt-textarea"
            :placeholder="'直接输入 Prompt 内容，支持 {query} 等占位符...'"
            id="textarea-prompt-content"
          ></textarea>
        </div>

        <!-- 说明 -->
        <div class="form-item">
          <label>中文说明</label>
          <input
            v-model="editForm.description"
            type="text"
            placeholder="简短描述此 Prompt 的用途（供团队成员理解）"
            id="input-prompt-desc"
          />
        </div>

        <!-- 修改人 -->
        <div class="form-item">
          <label>修改人</label>
          <input
            v-model="editForm.updatedBy"
            type="text"
            placeholder="请输入修改人姓名或工号"
            id="input-updated-by"
          />
        </div>

        <div class="modal-actions">
          <div class="modal-actions-left">
            <span class="version-hint">当前版本：v{{ editingItem.version }} → 保存后自动升为 v{{ editingItem.version + 1 }}</span>
          </div>
          <div class="modal-actions-right">
            <button class="btn-secondary" @click="closeEdit">取消</button>
            <button
              class="btn-primary"
              :disabled="saving"
              @click="saveEdit"
              id="btn-save-prompt"
            >
              {{ saving ? '保存中...' : '确认修改' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Toast 提示 -->
    <div v-if="toast.show" class="toast" :class="toast.type">{{ toast.msg }}</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import axios from 'axios';
import { API_BASE } from '../../utils/apiBase';

const BASE = `${API_BASE}/admin/prompts`;

// ── 场景中文说明 ───────────────────────────────────────────────
const SCENE_DESC = {
  HYDE_GENERAL:   '通用 HyDE：>20字含疑问词查询，生成假设文档',
  HYDE_SHORT:     '短查询预热：4~20字关键词后台异步预热语义缓存',
  HYDE_GONGSHU:   '公示专属 HyDE：人事任职公示类查询，生成人员名单格式',
  HYDE_FAGUI:     '法规专属 HyDE：法律法规查询，生成法律条文格式',
  HYDE_TONGZHI:   '通知专属 HyDE：通知公告查询，生成政务通知正文',
  REWRITE:        '意图改写：提取核心词 + 展开政务缩略语，优化 BM25 召回',
  RERANK:         'LLM 重排：批量为候选文档打 0-10 相关性得分',
  LEGACY_HYDE:    '旧版 HyDE：/api/ai/vector/hyde 接口专用，含 few-shot 示例',
};

// ── 各场景可用占位符提示 ────────────────────────────────────────
const PLACEHOLDER_HINT = {
  RERANK: ['{query}', '{docs}', '{doc_count}'],
  LEGACY_HYDE: ['{query}'],
};

// ── 响应式状态 ─────────────────────────────────────────────────
const promptList = ref([]);
const loading    = ref(true);
const saving     = ref(false);
const editingItem = ref(null);
const activeScene = ref('全部');
const collapsed   = ref({});   // {scene: bool}

const editForm = ref({ content: '', description: '', updatedBy: '' });
const toast    = ref({ show: false, msg: '', type: 'success' });

// ── 计算属性 ───────────────────────────────────────────────────

/** 所有 scene 枚举（有序去重） */
const scenes = computed(() => {
  const set = new Set(promptList.value.map(p => p.scene));
  return [...set];
});

/** 按 scene 分组，并按 activeScene 过滤 */
const filteredGroups = computed(() => {
  const list = activeScene.value === '全部'
    ? promptList.value
    : promptList.value.filter(p => p.scene === activeScene.value);

  return list.reduce((acc, item) => {
    if (!acc[item.scene]) acc[item.scene] = [];
    acc[item.scene].push(item);
    return acc;
  }, {});
});

// ── 工具方法 ───────────────────────────────────────────────────

const showToast = (msg, type = 'success') => {
  toast.value = { show: true, msg, type };
  setTimeout(() => { toast.value.show = false; }, 3500);
};

const toggleCollapse = (scene) => {
  collapsed.value[scene] = !collapsed.value[scene];
};

// ── API 调用 ───────────────────────────────────────────────────

const fetchList = async () => {
  loading.value = true;
  try {
    const res = await axios.get(BASE);
    if (res.data.code === 200) {
      promptList.value = res.data.data.records;
    }
  } catch(e) {
    showToast('加载 Prompt 列表失败: ' + e.message, 'error');
  } finally {
    loading.value = false;
  }
};

const openEdit = (item) => {
  editingItem.value = item;
  editForm.value = {
    content:     item.content,
    description: item.description || '',
    updatedBy:   '',
  };
};

const closeEdit = () => {
  editingItem.value = null;
};

const saveEdit = async () => {
  if (!editForm.value.content.trim()) {
    showToast('Prompt 正文不能为空', 'error');
    return;
  }
  saving.value = true;
  try {
    const res = await axios.put(
      `${BASE}/${editingItem.value.promptKey}`,
      editForm.value
    );
    if (res.data.code === 200) {
      showToast('✅ 已保存，AI 服务将在 60 秒内自动生效');
      closeEdit();
      await fetchList();
    } else {
      showToast('保存失败: ' + res.data.msg, 'error');
    }
  } catch(e) {
    showToast('保存失败: ' + e.message, 'error');
  } finally {
    saving.value = false;
  }
};

const toggleActive = async (item) => {
  const newState = item.isActive === 1 ? 0 : 1;
  const label = newState === 1 ? '启用' : '停用';
  try {
    const res = await axios.put(`${BASE}/${item.promptKey}`, { isActive: newState });
    if (res.data.code === 200) {
      showToast(`✅ 已${label}，AI 服务将在 60 秒内生效`);
      await fetchList();
    } else {
      showToast(`${label}失败: ` + res.data.msg, 'error');
    }
  } catch(e) {
    showToast(`${label}失败: ` + e.message, 'error');
  }
};

onMounted(fetchList);
</script>

<style scoped>
.prompt-container {
  max-width: 1100px;
  margin: 0 auto;
  animation: fadeIn 0.5s ease-out;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(12px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ── 页头 ──────────────────────────────────────────────────── */
.page-header {
  margin-bottom: 36px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  flex-wrap: wrap;
}
.page-header h2 {
  font-size: 28px; font-weight: 800;
  color: var(--text-main); margin: 0 0 8px 0; letter-spacing: -0.8px;
}
.subtitle {
  color: var(--text-muted); font-size: 14px; margin: 0; line-height: 1.6;
}
.subtitle strong { color: var(--accent-blue); }

/* ── 场景 Tab 过滤 ─────────────────────────────────────────── */
.scene-filter {
  display: flex; flex-wrap: wrap; gap: 6px;
}
.scene-tab {
  padding: 7px 14px;
  border-radius: 10px;
  font-size: 12px; font-weight: 600;
  border: 1px solid var(--border-light);
  background: var(--snow-bg);
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.15s ease;
}
.scene-tab:hover { border-color: var(--accent-blue); color: var(--accent-blue); }
.scene-tab.active {
  background: var(--accent-blue); color: white;
  border-color: var(--accent-blue);
}

/* ── 场景卡片 ─────────────────────────────────────────────── */
.scene-list { display: flex; flex-direction: column; gap: 16px; }

.scene-card {
  background: var(--snow-bg);
  border-radius: 20px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: box-shadow 0.2s ease;
}
.scene-card:hover { box-shadow: var(--shadow-md); }

.scene-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 24px;
  cursor: pointer;
  user-select: none;
  background: var(--snow-soft);
  border-bottom: 1px solid var(--border-ultra-light);
  transition: background 0.15s;
}
.scene-header:hover { background: #f0f4ff; }

.scene-title { display: flex; align-items: center; gap: 12px; }
.scene-badge {
  background: var(--accent-soft); color: var(--accent-blue);
  padding: 4px 12px; border-radius: 8px;
  font-size: 12px; font-weight: 700; font-family: monospace;
  white-space: nowrap;
}
.scene-desc { font-size: 13px; color: var(--text-muted); }
.scene-meta { display: flex; align-items: center; gap: 12px; }
.count-tag {
  font-size: 12px; color: var(--text-muted);
  background: var(--snow-bg); border: 1px solid var(--border-ultra-light);
  padding: 3px 8px; border-radius: 6px;
}
.collapse-icon { color: var(--text-muted); font-size: 11px; }

/* ── Prompt 条目 ──────────────────────────────────────────── */
.prompt-list { padding: 8px 0; }

.prompt-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-ultra-light);
  transition: background 0.15s;
}
.prompt-item:last-child { border-bottom: none; }
.prompt-item:hover { background: var(--snow-soft); }
.prompt-item.item-disabled { opacity: 0.5; }

.prompt-item-left { flex: 1; min-width: 0; }
.prompt-meta-row {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 12px; flex-wrap: wrap;
}

/* role badge */
.role-badge {
  padding: 3px 10px; border-radius: 6px;
  font-size: 11px; font-weight: 700; font-family: monospace;
  text-transform: uppercase;
}
.role-badge.system { background: #fef3c7; color: #92400e; }
.role-badge.user   { background: #ede9fe; color: #5b21b6; }

.prompt-key {
  font-size: 12px; color: var(--text-muted);
  background: var(--snow-soft); padding: 2px 8px; border-radius: 6px;
  font-family: monospace;
}
.status-pill {
  font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 20px;
}
.status-pill.active  { background: #d1fae5; color: #065f46; }
.status-pill.inactive { background: var(--snow-soft); color: var(--text-muted); }
.version-tag {
  font-size: 11px; color: var(--text-muted); font-family: monospace;
}

/* Prompt 正文预览 */
.prompt-preview {
  background: var(--snow-soft);
  border: 1px solid var(--border-ultra-light);
  border-radius: 10px;
  padding: 12px 16px;
  font-size: 13px;
  color: var(--text-main);
  font-family: 'SFMono-Regular', Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  line-height: 1.7;
  max-height: 80px;
  overflow: hidden;
  position: relative;
  margin: 0 0 8px;
}
.prompt-preview::after {
  content: '';
  position: absolute; bottom: 0; left: 0; right: 0;
  height: 32px;
  background: linear-gradient(transparent, var(--snow-soft));
  pointer-events: none;
}
.prompt-desc { font-size: 12px; color: var(--text-muted); margin: 0; }

/* 条目操作按钮 */
.prompt-item-actions {
  display: flex; flex-direction: column; gap: 8px; flex-shrink: 0;
}
.btn-edit, .btn-toggle {
  padding: 7px 16px; border-radius: 9px;
  font-size: 12px; font-weight: 600; cursor: pointer;
  transition: all 0.15s ease; white-space: nowrap;
}
.btn-edit {
  background: var(--accent-soft); color: var(--accent-blue);
  border: 1px solid var(--border-light);
}
.btn-edit:hover { background: var(--accent-blue); color: white; }
.btn-toggle {
  background: var(--snow-soft); color: var(--text-muted);
  border: 1px solid var(--border-light);
}
.btn-toggle:hover { color: var(--text-main); background: var(--border-ultra-light); }

/* ── Modal ─────────────────────────────────────────────────── */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000; backdrop-filter: blur(6px);
  animation: fadeIn 0.2s ease-out;
}
.modal-card {
  background: var(--snow-bg);
  border-radius: 24px;
  padding: 40px;
  width: 700px; max-width: 95vw; max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-ultra-light);
}
.modal-header { margin-bottom: 24px; }
.modal-title { font-size: 20px; font-weight: 800; margin: 0 0 8px; }
.modal-subtitle { display: flex; align-items: center; gap: 8px; margin: 0; }

/* 占位符提示 */
.placeholder-hint {
  background: #f0f4ff;
  border: 1px solid #c7d6ff;
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 20px;
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
}
.hint-title { font-size: 12px; color: #3b5bdb; font-weight: 600; }
.ph-chip {
  background: #dbe4ff; color: #3b5bdb;
  padding: 2px 8px; border-radius: 6px; font-size: 12px; font-family: monospace;
}

/* 表单 */
.form-item { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
.form-item label { font-size: 13px; font-weight: 600; color: var(--text-main); }
.required { color: #ef4444; }
.form-item input {
  padding: 11px 14px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--snow-soft);
  color: var(--text-main); font-size: 14px; outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.form-item input:focus {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.prompt-textarea {
  width: 100%; box-sizing: border-box;
  padding: 14px 16px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 13px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  line-height: 1.7; resize: vertical; outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.prompt-textarea:focus {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

/* Modal 底部 */
.modal-actions {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 28px; padding-top: 20px;
  border-top: 1px solid var(--border-ultra-light);
}
.modal-actions-left { flex: 1; }
.version-hint { font-size: 12px; color: var(--text-muted); }
.modal-actions-right { display: flex; gap: 12px; }

.btn-primary {
  background: var(--accent-blue); color: white;
  padding: 12px 28px; border-radius: 12px;
  font-size: 14px; font-weight: 700; cursor: pointer;
  transition: all 0.2s ease;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
}
.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px); box-shadow: 0 8px 20px rgba(37, 99, 235, 0.3);
}
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-secondary {
  padding: 12px 28px; border: 1px solid var(--border-light);
  border-radius: 12px; background: var(--snow-bg);
  color: var(--text-main); font-size: 14px; font-weight: 600;
  cursor: pointer; transition: background 0.15s;
}
.btn-secondary:hover { background: var(--snow-soft); }

/* ── Toast ─────────────────────────────────────────────────── */
.toast {
  position: fixed; bottom: 32px; right: 32px;
  padding: 14px 24px; border-radius: 14px;
  font-size: 14px; font-weight: 600;
  z-index: 9999; animation: slideUp 0.3s ease-out;
  box-shadow: var(--shadow-lg);
}
.toast.success { background: #d1fae5; color: #065f46; border: 1px solid #6ee7b7; }
.toast.error   { background: #fee2e2; color: #991b1b; border: 1px solid #fca5a5; }
@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* ── 其他 ─────────────────────────────────────────────────── */
.loading-state { text-align: center; padding: 80px; color: var(--text-muted); font-size: 15px; }
.empty-tip     { text-align: center; padding: 60px; color: var(--text-muted); font-size: 14px; }
.empty-tip code {
  background: var(--snow-soft); padding: 2px 8px; border-radius: 6px;
  font-family: monospace; font-size: 13px;
}
</style>
