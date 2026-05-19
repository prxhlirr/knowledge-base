<template>
  <div class="synonym-container">
    <header class="page-header">
      <h2>政务同义词词典</h2>
      <p class="subtitle">维护缩略语与等价词映射，无需重启服务即可热更新检索词典，消除词汇鸿沟</p>
    </header>

    <!-- 操作栏 -->
    <div class="action-row">
      <div class="search-box">
        <span class="search-icon">🔍</span>
        <input
          v-model="testQuery"
          type="text"
          placeholder="输入检索词测试展开效果（如：环评 城管）"
          @keyup.enter="testExpand"
          id="synonym-test-input"
        />
        <button class="btn-test" :disabled="testing" @click="testExpand" id="btn-test-expand">
          {{ testing ? '测试中...' : '测试展开' }}
        </button>
      </div>
      <button class="btn-primary" @click="showAddModal = true" id="btn-add-synonym">
        ＋ 添加词条
      </button>
    </div>

    <!-- 展开测试结果 -->
    <div v-if="expandResult" class="expand-result-card" :class="{ 'has-expansion': expandResult.hasExpansion }">
      <div class="expand-header">
        <span class="expand-icon">{{ expandResult.hasExpansion ? '✅' : 'ℹ️' }}</span>
        <strong>查询词：</strong>「{{ expandResult.originalQuery }}」
        <span class="expand-tag" v-if="expandResult.hasExpansion">命中 {{ Object.keys(expandResult.expansions).length }} 个缩略语</span>
        <span class="expand-tag no-hit" v-else>未命中任何缩略语，走原始 BM25</span>
      </div>
      <div v-if="expandResult.hasExpansion" class="expand-detail">
        <div v-for="(terms, abbr) in expandResult.expansions" :key="abbr" class="expansion-item">
          <span class="abbr-chip">{{ abbr }}</span>
          <span class="arrow">→</span>
          <span class="terms-list">{{ terms.join('  /  ') }}</span>
        </div>
      </div>
    </div>

    <!-- 词条列表 -->
    <div class="table-section">
      <div v-if="loading" class="loading-state">正在加载词典...</div>
      <table v-else class="synonym-table">
        <thead>
          <tr>
            <th>缩略词 / 触发词</th>
            <th>展开词列表</th>
            <th>类型</th>
            <th>状态</th>
            <th>备注</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in synonymList" :key="item.id" :class="{ 'row-disabled': item.enabled === 0 }">
            <td><span class="abbr-badge">{{ item.abbr }}</span></td>
            <td class="terms-cell">{{ item.fullTerms }}</td>
            <td>
              <span class="type-tag" :class="item.synonymType === 'ABBR' ? 'type-abbr' : 'type-equiv'">
                {{ item.synonymType === 'ABBR' ? '缩略 →' : '≡ 等价' }}
              </span>
            </td>
            <td>
              <span class="status-dot" :class="item.enabled === 1 ? 'active' : 'inactive'"></span>
              {{ item.enabled === 1 ? '启用' : '停用' }}
            </td>
            <td class="remark-cell">{{ item.remark || '—' }}</td>
            <td class="action-cell">
              <button class="btn-edit" @click="startEdit(item)" :id="'btn-edit-' + item.id">编辑</button>
              <button
                class="btn-toggle"
                @click="toggleEnabled(item)"
                :id="'btn-toggle-' + item.id"
              >{{ item.enabled === 1 ? '停用' : '启用' }}</button>
              <button class="btn-delete" @click="deleteItem(item)" :id="'btn-del-' + item.id">删除</button>
            </td>
          </tr>
          <tr v-if="synonymList.length === 0">
            <td colspan="6" class="empty-tip">暂无词条，点击右上角「添加词条」开始配置</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新增/编辑 Modal -->
    <div v-if="showAddModal || editingItem" class="modal-overlay" @click.self="closeModal">
      <div class="modal-card">
        <h3 class="modal-title">{{ editingItem ? '编辑同义词条目' : '新增同义词条目' }}</h3>

        <div class="modal-form">
          <div class="form-item">
            <label>缩略词 / 触发词 <span class="required">*</span></label>
            <input v-model="formData.abbr" type="text" placeholder="如：环评" id="input-abbr"
              :disabled="!!editingItem" />
            <p class="hint">检索时包含此词即触发展开，已有词条不可修改（请删除后重建）</p>
          </div>
          <div class="form-item">
            <label>展开词列表 <span class="required">*</span></label>
            <input v-model="formData.fullTerms" type="text"
              placeholder="如：环境影响评价,环境评价（逗号分隔）" id="input-full-terms" />
            <p class="hint">多个词用英文逗号分隔。BM25 会对每个展开词单独构建 should 子句。</p>
          </div>
          <div class="form-grid">
            <div class="form-item">
              <label>词条类型</label>
              <select v-model="formData.synonymType" id="select-type">
                <option value="ABBR">ABBR — 缩略语（单向展开）</option>
                <option value="EQUIV">EQUIV — 等价词（双向展开）</option>
              </select>
              <p class="hint">EQUIV 类型下，展开词中的每个词也作为触发键反向命中。</p>
            </div>
            <div class="form-item">
              <label>启用状态</label>
              <select v-model="formData.enabled" id="select-enabled">
                <option :value="1">启用</option>
                <option :value="0">停用（保留记录）</option>
              </select>
            </div>
          </div>
          <div class="form-item">
            <label>备注</label>
            <input v-model="formData.remark" type="text"
              placeholder="如：《环境影响评价法》标准缩略语" id="input-remark" />
          </div>
        </div>

        <div class="modal-actions">
          <button class="btn-secondary" @click="closeModal">取消</button>
          <button class="btn-primary" :disabled="saving" @click="saveItem" id="btn-save-synonym">
            {{ saving ? '保存中...' : (editingItem ? '确认修改' : '确认添加') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';

const BASE = `${import.meta.env.VITE_API_BASE_URL}/admin/synonyms`;

const synonymList = ref([]);
const loading = ref(true);
const saving = ref(false);
const showAddModal = ref(false);
const editingItem = ref(null);
const testQuery = ref('');
const testing = ref(false);
const expandResult = ref(null);

const formData = ref({
  abbr: '', fullTerms: '', synonymType: 'ABBR', enabled: 1, remark: ''
});

const fetchList = async () => {
  loading.value = true;
  try {
    const res = await axios.get(BASE);
    if (res.data.code === 200) synonymList.value = res.data.data.records;
  } catch (e) {
    alert('获取词典失败: ' + e.message);
  } finally {
    loading.value = false;
  }
};

const startEdit = (item) => {
  editingItem.value = item;
  formData.value = { ...item };
};

const closeModal = () => {
  showAddModal.value = false;
  editingItem.value = null;
  formData.value = { abbr: '', fullTerms: '', synonymType: 'ABBR', enabled: 1, remark: '' };
};

const saveItem = async () => {
  if (!formData.value.abbr.trim()) { alert('缩略词不能为空'); return; }
  if (!formData.value.fullTerms.trim()) { alert('展开词列表不能为空'); return; }
  saving.value = true;
  try {
    let res;
    if (editingItem.value) {
      res = await axios.put(`${BASE}/${editingItem.value.id}`, formData.value);
    } else {
      res = await axios.post(BASE, formData.value);
    }
    if (res.data.code === 200) {
      alert(res.data.msg || '保存成功，缓存已热更新');
      closeModal();
      await fetchList();
    } else {
      alert('保存失败: ' + res.data.msg);
    }
  } catch (e) {
    alert('保存失败: ' + e.message);
  } finally {
    saving.value = false;
  }
};

const toggleEnabled = async (item) => {
  const updated = { ...item, enabled: item.enabled === 1 ? 0 : 1 };
  try {
    const res = await axios.put(`${BASE}/${item.id}`, updated);
    if (res.data.code === 200) await fetchList();
    else alert('操作失败: ' + res.data.msg);
  } catch (e) {
    alert('操作失败: ' + e.message);
  }
};

const deleteItem = async (item) => {
  if (!confirm(`确认删除词条「${item.abbr}」？此操作不可恢复。`)) return;
  try {
    const res = await axios.delete(`${BASE}/${item.id}`);
    if (res.data.code === 200) {
      alert('删除成功，缓存已热更新');
      await fetchList();
    } else {
      alert('删除失败: ' + res.data.msg);
    }
  } catch (e) {
    alert('删除失败: ' + e.message);
  }
};

const testExpand = async () => {
  if (!testQuery.value.trim()) return;
  testing.value = true;
  expandResult.value = null;
  try {
    const res = await axios.get(`${BASE}/expand`, { params: { query: testQuery.value } });
    if (res.data.code === 200) expandResult.value = res.data;
    else alert('测试失败: ' + res.data.msg);
  } catch (e) {
    alert('测试失败: ' + e.message);
  } finally {
    testing.value = false;
  }
};

onMounted(fetchList);
</script>

<style scoped>
.synonym-container {
  max-width: 1100px;
  margin: 0 auto;
  animation: fadeIn 0.6s ease-out;
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

.page-header { margin-bottom: 40px; }
.page-header h2 { font-size: 30px; font-weight: 800; color: var(--text-main); margin: 0 0 10px 0; letter-spacing: -0.8px; }
.subtitle { color: var(--text-muted); font-size: 15px; margin: 0; }

/* 操作栏 */
.action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}
.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  background: var(--snow-bg);
  border: 1px solid var(--border-light);
  border-radius: 14px;
  padding: 4px 12px;
  gap: 8px;
}
.search-icon { font-size: 16px; color: var(--text-muted); }
.search-box input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: var(--text-main);
  padding: 10px 0;
}
.btn-test {
  padding: 8px 18px;
  background: var(--accent-soft);
  color: var(--accent-blue);
  border: 1px solid var(--accent-blue);
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition-fast);
  white-space: nowrap;
}
.btn-test:hover:not(:disabled) { background: var(--accent-blue); color: white; }
.btn-test:disabled { opacity: 0.6; cursor: not-allowed; }

/* 展开结果卡片 */
.expand-result-card {
  background: var(--snow-soft);
  border: 1px solid var(--border-light);
  border-radius: 14px;
  padding: 16px 20px;
  margin-bottom: 24px;
  border-left: 4px solid var(--text-muted);
}
.expand-result-card.has-expansion { border-left-color: #10B981; }
.expand-header { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.expand-icon { font-size: 16px; }
.expand-tag {
  background: #d1fae5; color: #065f46;
  padding: 2px 8px; border-radius: 6px;
  font-size: 12px; font-weight: 600;
}
.expand-tag.no-hit { background: var(--snow-bg); color: var(--text-muted); }
.expand-detail { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.expansion-item { display: flex; align-items: center; gap: 10px; }
.abbr-chip {
  background: var(--accent-soft); color: var(--accent-blue);
  padding: 3px 10px; border-radius: 8px; font-weight: 700; font-size: 13px;
}
.arrow { color: var(--text-muted); font-weight: 700; }
.terms-list { font-size: 14px; color: var(--text-main); font-family: monospace; }

/* 表格 */
.table-section {
  background: var(--snow-bg);
  border-radius: 24px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}
.synonym-table { width: 100%; border-collapse: collapse; }
.synonym-table thead th {
  padding: 16px 20px;
  background: var(--snow-soft);
  font-size: 12px;
  font-weight: 700;
  color: var(--text-muted);
  text-align: left;
  border-bottom: 1px solid var(--border-ultra-light);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.synonym-table tbody tr {
  border-bottom: 1px solid var(--border-ultra-light);
  transition: background 0.15s;
}
.synonym-table tbody tr:last-child { border-bottom: none; }
.synonym-table tbody tr:hover { background: var(--snow-soft); }
.synonym-table tbody td { padding: 14px 20px; font-size: 14px; color: var(--text-main); }
.row-disabled td { opacity: 0.45; }

.abbr-badge {
  background: var(--accent-soft); color: var(--accent-blue);
  padding: 3px 10px; border-radius: 8px;
  font-weight: 700; font-size: 13px;
}
.terms-cell { font-family: monospace; font-size: 13px; color: var(--text-muted); max-width: 320px; }
.type-tag {
  padding: 3px 8px; border-radius: 6px;
  font-size: 12px; font-weight: 600;
}
.type-abbr { background: #fef3c7; color: #92400e; }
.type-equiv { background: #ede9fe; color: #5b21b6; }
.status-dot {
  display: inline-block;
  width: 8px; height: 8px; border-radius: 50%;
  margin-right: 6px;
}
.status-dot.active { background: #10B981; }
.status-dot.inactive { background: var(--text-muted); }
.remark-cell { color: var(--text-muted); font-size: 13px; max-width: 200px; }
.action-cell { display: flex; gap: 8px; }
.btn-edit, .btn-toggle, .btn-delete {
  padding: 6px 12px; border-radius: 8px;
  font-size: 12px; font-weight: 600; cursor: pointer;
  transition: var(--transition-fast);
}
.btn-edit { background: var(--accent-soft); color: var(--accent-blue); border: 1px solid var(--border-light); }
.btn-edit:hover { background: var(--accent-blue); color: white; }
.btn-toggle { background: var(--snow-soft); color: var(--text-muted); border: 1px solid var(--border-light); }
.btn-toggle:hover { background: var(--border-light); color: var(--text-main); }
.btn-delete { background: #fee2e2; color: #991b1b; border: 1px solid #fca5a5; }
.btn-delete:hover { background: #ef4444; color: white; border-color: #ef4444; }
.empty-tip { text-align: center; color: var(--text-muted); padding: 60px 0; }
.loading-state { text-align: center; padding: 80px; color: var(--text-muted); font-size: 15px; }

/* Modal */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.3);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
  animation: fadeIn 0.2s ease-out;
}
.modal-card {
  background: var(--snow-bg);
  border-radius: 24px;
  padding: 40px;
  width: 560px;
  max-width: 95vw;
  box-shadow: var(--shadow-lg);
  border: 1px solid var(--border-ultra-light);
}
.modal-title { font-size: 20px; font-weight: 800; margin: 0 0 28px 0; color: var(--text-main); }
.modal-form { display: flex; flex-direction: column; gap: 20px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.form-item { display: flex; flex-direction: column; gap: 8px; }
.form-item label { font-size: 13px; font-weight: 600; color: var(--text-main); }
.required { color: #ef4444; }
.form-item input, .form-item select {
  padding: 11px 14px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  outline: none;
  transition: var(--transition-fast);
}
.form-item input:focus, .form-item select:focus {
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.form-item input:disabled { opacity: 0.5; cursor: not-allowed; }
.hint { font-size: 12px; color: var(--text-muted); margin: 0; line-height: 1.5; }
.modal-actions {
  display: flex; justify-content: flex-end; gap: 12px;
  margin-top: 32px;
  padding-top: 20px;
  border-top: 1px solid var(--border-ultra-light);
}

.btn-primary {
  background: var(--accent-blue); color: white;
  padding: 12px 28px; border-radius: 12px;
  font-size: 14px; font-weight: 700;
  cursor: pointer; transition: var(--transition-smooth);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
}
.btn-primary:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(37, 99, 235, 0.3); }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-secondary {
  padding: 12px 28px; border: 1px solid var(--border-light);
  border-radius: 12px; background: var(--snow-bg);
  color: var(--text-main); font-size: 14px; font-weight: 600;
  cursor: pointer; transition: var(--transition-smooth);
}
.btn-secondary:hover { background: var(--snow-soft); }
</style>
