<template>
  <div class="doc-import-container">
    <header class="page-header">
      <h2>批量文档接入与向量化管线</h2>
      <p class="subtitle">拉取指定目录档案，进行格式防伪、脱敏分片与大模型嵌入提取</p>
    </header>

    <section class="import-section">
      <h3 class="section-title">发起新批次入库</h3>
      <div class="import-type-selector">
        <label><input type="radio" value="LOCAL" v-model="importType" /> 服务器本地目录</label>
        <label><input type="radio" value="HTTP" v-model="importType" /> 本地文件上传</label>
        <label><input type="radio" value="URL" v-model="importType" /> 直链下载</label>
        <label><input type="radio" value="SFTP" v-model="importType" /> SFTP 拉取</label>
      </div>

      <div class="import-form wrap-form" style="margin-bottom: 20px;">
        <input type="text" v-model="commonForm.targetIndex" placeholder="目标索引 (默认: kb_document_v1)" class="half-input" />
        <div style="position: relative;">
          <input 
            type="text" 
            v-model="commonForm.tag" 
            @input="handleTagInput"
            placeholder="搜索或输入文档标签 (支持动态匹配)" 
            class="half-input" 
            style="width: 100%;"
            list="tag-options"
          />
          <datalist id="tag-options">
            <option v-for="t in tagOptions" :key="t" :value="t"></option>
          </datalist>
        </div>
        <input type="text" v-model="commonForm.unit" placeholder="来源单位" class="half-input" />
        <input type="text" v-model="commonForm.docNumber" placeholder="文件文号 (如: 国发〔2026〕1号)" class="half-input" />
        <input type="text" v-model="commonForm.owner" placeholder="所属人员 (如: 张三)" class="half-input" />
        <textarea v-model="commonForm.searchQueries" placeholder="搜补关键词大字段 (输入与此文档相关的长难搜索词以强行提升命中率)" class="full-input" style="grid-column: span 2; min-height: 80px; resize: vertical;"></textarea>
        <!-- [Phase 3] 权限与部门字段 (C2/C4) -->
        <div class="select-wrapper">
          <label class="field-label">可见度</label>
          <select v-model="commonForm.visibility" class="half-input select-input">
            <option value="INTERNAL">INTERNAL — 内部可见（默认）</option>
            <option value="PUBLIC">PUBLIC — 公开</option>
            <option value="DEPT">DEPT — 仅指定部门</option>
            <option value="PRIVATE">PRIVATE — 仅上传人</option>
            <option value="GRANT">GRANT — 手动授权</option>
          </select>
        </div>
        <div>
          <label class="field-label">部门编码
            <span v-if="commonForm.visibility === 'DEPT'" class="dept-hint">⚠️ DEPT 模式必填</span>
          </label>
          <input
            type="text"
            v-model="commonForm.deptCode"
            placeholder="12位行政区划编码 (如: 620102900000)"
            class="half-input"
            :class="{ 'dept-required': commonForm.visibility === 'DEPT' && !commonForm.deptCode }"
          />
        </div>
      </div>

      <div class="import-form" v-if="importType === 'LOCAL'">
        <input type="text" v-model="localForm.directoryPath" placeholder="请输入服务器下绝对路径 (如 E:\doc)" class="full-input" />
      </div>
      <div class="import-form" v-if="importType === 'HTTP'">
        <input type="file" multiple @change="handleFileChange" class="full-input" />
      </div>
      <div class="import-form" v-if="importType === 'URL'">
        <input type="text" v-model="urlForm.url" placeholder="请求直链 URL (如 http://example.com/file.pdf)" class="full-input" />
      </div>
      <div class="import-form wrap-form" v-if="importType === 'SFTP'">
        <input type="text" v-model="sftpForm.host" placeholder="主机 (如 192.168.1.100)" class="half-input"/>
        <input type="number" v-model="sftpForm.port" placeholder="端口" class="half-input"/>
        <input type="text" v-model="sftpForm.user" placeholder="用户名" class="half-input"/>
        <input type="password" v-model="sftpForm.pass" placeholder="密码" class="half-input"/>
        <input type="text" v-model="sftpForm.remotePath" placeholder="远程抓取目录" class="full-input"/>
      </div>

      <div class="action-row">
        <button class="btn-primary" :disabled="importing" @click="startImport">
          {{ importing ? '提交中...' : '开始导入批次' }}
        </button>
      </div>
    </section>

    <section class="batch-section">
      <h3 class="section-title">历史批次看板</h3>
      <div class="table-actions">
        <button class="btn-secondary" @click="fetchBatches">🔄 刷新列表</button>
      </div>
      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th>批次号 (Batch ID)</th>
              <th>目录源</th>
              <th>总篇数</th>
              <th>成功进度</th>
              <th>异常数</th>
              <th>当前状态</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in batches" :key="b.batchId">
              <td class="hash-col">{{ b.batchId.substring(0, 8) }}...</td>
              <td>{{ b.sourceDir }}</td>
              <td>{{ b.totalCount }}</td>
              <td>
                <div class="progress-bar">
                  <div class="progress-fill" :style="{ width: ((b.successCount / (b.totalCount || 1)) * 100) + '%' }"></div>
                  <span class="progress-text">{{ b.successCount }} / {{ b.totalCount }}</span>
                </div>
              </td>
              <td :class="{ 'error-text': b.errorCount > 0 }">{{ b.errorCount }}</td>
              <td>
                <span :class="'status-tag ' + b.status.toLowerCase()">{{ b.status }}</span>
              </td>
              <td class="time-col">{{ formatDate(b.createdAt) }}</td>
            </tr>
            <tr v-if="batches.length === 0">
              <td colspan="7" class="empty-text">尚无汇入批次，快发起第一次接入吧</td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <!-- 批次列表分页 -->
      <div class="pagination" v-if="batchesTotal > batchesSize">
        <button class="page-btn" @click="changePage(batchesCurrent - 1)" :disabled="batchesCurrent === 1">‹</button>
        <span class="page-info">第 {{ batchesCurrent }} / {{ Math.ceil(batchesTotal / batchesSize) }} 页</span>
        <button class="page-btn" @click="changePage(batchesCurrent + 1)" :disabled="batchesCurrent >= Math.ceil(batchesTotal / batchesSize)">›</button>
      </div>

    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1';
// 内部服务凭证：从环境变量读取，兜底使用 application.yml 默认值
// 生产环境请在 .env.production 中设置 VITE_INTERNAL_TOKEN=<实际 token>
const INTERNAL_TOKEN = import.meta.env.VITE_INTERNAL_TOKEN || 'kb-dev-token-change-me-in-prod';
/** 统一 admin 请求头，避免重复声明 */
const adminHeaders = { 'X-Internal-Token': INTERNAL_TOKEN };

const importType = ref('LOCAL');
const localForm = ref({ directoryPath: '' });
const urlForm = ref({ url: '' });
const sftpForm = ref({ host: '', port: 22, user: '', pass: '', remotePath: '' });

// 标签动态获取与防抖逻辑
const tagOptions = ref(['政策', '公告', '汇报', '制度', '其他']);
let tagSearchTimer = null;
const handleTagInput = (e) => {
  const keyword = e.target.value;
  if (tagSearchTimer) clearTimeout(tagSearchTimer);
  
  tagSearchTimer = setTimeout(async () => {
    try {
      // 预留接口请求：未来放开注释即可支持后端检索
      // const res = await axios.get(`${API_BASE}/admin/dict/tags`, { params: { keyword } });
      // if (res?.data?.code === 200) {
      //   tagOptions.value = res.data.data;
      // }
      
      // 本地 Mock 防抖筛选效果
      const mockDict = ['政策', '公告', '汇报', '制度', '其他', '通知', '指引', '纪要', '规范'];
      if (!keyword) {
        tagOptions.value = ['政策', '公告', '汇报', '制度', '其他'];
      } else {
        const matches = mockDict.filter(t => t.includes(keyword) || keyword.includes(t));
        // 若找不到则把当前用户输入的作为一种可选值提示
        tagOptions.value = matches.length > 0 ? matches : [keyword]; 
      }
    } catch (err) {
      console.warn("请求标签字典失败，使用本地备选", err);
    }
  }, 500); // 500ms 防抖
};

const commonForm = ref({
  targetIndex: 'kb_document_v1',
  tag: '',
  unit: '',
  docNumber: '',
  owner: '',
  searchQueries: '',
  // [Phase 3] C2/C1 权限与部门字段
  visibility: 'INTERNAL',
  deptCode: ''
});
const filesToUpload = ref([]);

const handleFileChange = (e) => {
  filesToUpload.value = e.target.files;
};

const importing = ref(false);
const batches = ref([]);
const batchesTotal = ref(0);
const batchesCurrent = ref(1);
const batchesSize = ref(10);

let pollTimer = null;
let consecutiveErrors = 0; // 用于实现错误退避 (Exponential Backoff)

const startPolling = () => {
  if (pollTimer) clearTimeout(pollTimer);
  // 指数退避：失败次数越多，重试间隔越长（最大限制 60s）
  const interval = Math.min(5000 * Math.pow(2, consecutiveErrors), 60000);
  pollTimer = setTimeout(fetchBatches, interval);
};

const stopPolling = () => {
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
};

const fetchBatches = async () => {
  try {
    const res = await axios.get(`${API_BASE}/admin/doc/batches`, { 
      headers: adminHeaders,
      params: { current: batchesCurrent.value, size: batchesSize.value }
    });
    if (res.data.code === 200) {
      batches.value = res.data.data.records;
      batchesTotal.value = res.data.data.total;
      consecutiveErrors = 0; // 成功后重置错误计数
      
      // 智能探针：若当前列表中有运行态任务，则继续下次轮询；否则静默
      const hasActive = batches.value.some(b => ['PENDING', 'PROCESSING', 'IMPORTING'].includes(b.status));
      if (hasActive) {
        startPolling();
      } else {
        stopPolling();
      }
    } else {
      throw new Error(res.data.msg);
    }
  } catch (error) {
    console.error('获取批次列表失败', error);
    consecutiveErrors++;
    // 如果发生连接拒绝等错误，按退避策略重试，达到 8 次以上连续失败则彻底挂起以保护浏览器
    if (consecutiveErrors < 8) {
      startPolling();
    } else {
      stopPolling();
    }
  }
};

const changePage = (p) => {
  if (p < 1 || p > Math.ceil(batchesTotal.value / batchesSize.value)) return;
  batchesCurrent.value = p;
  // 翻页时立刻查询（fetchBatches 内部也会重新判定是否触发定时器）
  fetchBatches();
};

/** 文件大小上限（与后端保持一致）: 100 MB */
const MAX_FILE_SIZE_MB = 100;

const startImport = async () => {
  if (importing.value) return;

  // ──────────────────────────────────────────────────────────
  // [P1 #12] 前端 deptCode 硬校验（后端也会校验，前端提前拦截减少往返）
  // ──────────────────────────────────────────────────────────
  if (commonForm.value.visibility === 'DEPT' && !commonForm.value.deptCode.trim()) {
    alert('可见度为 DEPT 时，部门编码(deptCode)不能为空！\n请填写 12 位行政区划编码后再提交。');
    return;
  }

  importing.value = true;
  try {
    let res;
    if (importType.value === 'LOCAL') {
        if (!localForm.value.directoryPath) {
          alert('请输入目录'); importing.value = false; return;
        }
        // 本地目录导入接口需要 X-Internal-Token 鉴权
        res = await axios.post(`${API_BASE}/admin/doc/batch_import`, {
            sourceDir: localForm.value.directoryPath,
            ...commonForm.value
        }, { headers: adminHeaders });
    } else if (importType.value === 'HTTP') {
        if (filesToUpload.value.length === 0) {
          alert('请选择文件'); importing.value = false; return;
        }

        // [P2 #2] 前端文件大小预检（超大文件提前告知用户，不提交）
        const oversized = Array.from(filesToUpload.value)
          .filter(f => f.size > MAX_FILE_SIZE_MB * 1024 * 1024)
          .map(f => `${f.name} (${(f.size / 1024 / 1024).toFixed(1)} MB)`);
        if (oversized.length > 0) {
          alert(`以下文件超过 ${MAX_FILE_SIZE_MB}MB 限制，无法上传：\n${oversized.join('\n')}`);
          importing.value = false; return;
        }

        // [P2 #3] 重复文件预警：逐一检测是否已存在同名文档
        const fileList = Array.from(filesToUpload.value);
        const dupWarnings = [];
        for (const f of fileList) {
          try {
            const chk = await axios.get(`${API_BASE}/admin/docs/check`, { params: { name: f.name } });
            if (chk.data.exists) {
              dupWarnings.push(`「${f.name}」已存在 v${chk.data.version}，将创建新版本`);
            }
          } catch { /* check 接口失败不阻塞上传 */ }
        }
        if (dupWarnings.length > 0) {
          const confirmed = confirm(
            `以下文档已存在，继续上传将自动递增版本号：\n\n${dupWarnings.join('\n')}\n\n确认继续？`
          );
          if (!confirmed) { importing.value = false; return; }
        }

        const formData = new FormData();
        fileList.forEach(f => formData.append('files', f));
        Object.keys(commonForm.value).forEach(key => formData.append(key, commonForm.value[key] || ''));
        res = await axios.post(`${API_BASE}/admin/doc/upload`, formData, { headers: adminHeaders });
    } else if (importType.value === 'URL') {
        if (!urlForm.value.url) { alert('请输入URL'); importing.value = false; return; }
        // URL 下载导入接口需要 X-Internal-Token 鉴权
        res = await axios.post(`${API_BASE}/admin/doc/url_import`, {
            url: urlForm.value.url, name: '', ...commonForm.value
        }, { headers: adminHeaders });
    } else if (importType.value === 'SFTP') {
        if (!sftpForm.value.host) { alert('请填写主机'); importing.value = false; return; }
        // SFTP 拉取接口需要 X-Internal-Token 鉴权
        res = await axios.post(`${API_BASE}/admin/doc/sftp_import`, {
            ...sftpForm.value, ...commonForm.value
        }, { headers: adminHeaders });
    }

    if (res && res.data.code === 200) {
      alert('导入任务已提交！\n' + res.data.msg);
      batchesCurrent.value = 1; // 回到首页以查看最新批次
      fetchBatches();
    } else if (res && res.data.msg) {
      alert('提交失败: ' + res.data.msg);
    }
  } catch (error) {
    console.error('API Error:', error);
    alert('请求异常，请检查后端运行状态: ' + (error.response?.data?.msg || error.message));
  } finally {
    importing.value = false;
  }
};

const formatDate = (dateStr) => {
  if (!dateStr) return '---';
  return new Date(dateStr).toLocaleString();
};

onMounted(() => {
  fetchBatches(); // 只需调用一次，fetchBatches 内能自动决定是否开始 setTimeout
});
</script>

<style scoped>
.doc-import-container {
  max-width: 1200px;
  margin: 0 auto;
  animation: fadeIn 0.8s ease-out;
  display: flex;
  flex-direction: column;
  gap: 40px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 分页 */
.pagination { display: flex; justify-content: center; align-items: center; gap: 16px; padding-top: 24px; padding-bottom: 8px; }
.page-btn { padding: 8px 16px; border-radius: 10px; background: var(--snow-soft); color: var(--text-main); font-weight: 600; cursor: pointer; border: none; transition: var(--transition-fast) }
.page-btn:hover:not(:disabled) { background: var(--border-light); }
.page-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.page-info { font-size: 13px; color: var(--text-muted); font-weight: 600; }

.page-header {
  margin-bottom: 8px;
}

.page-header h2 {
  font-size: 32px;
  font-weight: 800;
  color: var(--text-main);
  margin: 0 0 12px 0;
  letter-spacing: -1px;
}

.subtitle {
  color: var(--text-muted);
  font-size: 16px;
  font-weight: 400;
}

.import-section, .batch-section {
  background: var(--snow-bg);
  padding: 40px;
  border-radius: 24px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
  transition: var(--transition-smooth);
}

.import-section:hover, .batch-section:hover {
  box-shadow: var(--shadow-lg);
}

.section-title {
  font-size: 18px;
  font-weight: 700;
  margin-bottom: 32px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-ultra-light);
  color: var(--accent-blue);
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

.import-type-selector {
  display: flex;
  gap: 24px;
  margin-bottom: 32px;
  padding: 8px;
  background: var(--snow-soft);
  border-radius: 16px;
  width: fit-content;
}

.import-type-selector label {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border-radius: 12px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-muted);
  transition: var(--transition-fast);
}

.import-type-selector label:has(input:checked) {
  background: var(--snow-bg);
  color: var(--accent-blue);
  box-shadow: var(--shadow-sm);
}

.import-type-selector input {
  display: none;
}

.import-form {
  margin-bottom: 32px;
  animation: slideIn 0.4s ease-out;
}

@keyframes slideIn {
  from { opacity: 0; transform: translateX(-10px); }
  to { opacity: 1; transform: translateX(0); }
}

.wrap-form {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.half-input, .full-input {
  width: 100%;
  padding: 14px 20px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  transition: var(--transition-fast);
  outline: none;
}

.half-input:focus, .full-input:focus {
  border-color: var(--accent-blue);
  background: var(--snow-bg);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.action-row {
  display: flex;
  justify-content: flex-end;
}

.btn-primary {
  background: var(--accent-blue);
  color: white;
  padding: 14px 32px;
  border-radius: 14px;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
  transition: var(--transition-smooth);
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

.btn-secondary {
  background: var(--snow-bg);
  border: 1px solid var(--border-light);
  padding: 10px 20px;
  border-radius: 12px;
  color: var(--text-main);
  font-size: 14px;
  font-weight: 600;
  transition: var(--transition-fast);
  display: flex;
  align-items: center;
  gap: 8px;
}

.btn-secondary:hover {
  background: var(--snow-soft);
  border-color: var(--text-muted);
}

.table-actions {
  margin-bottom: 24px;
}

.table-container {
  overflow-x: auto;
  border-radius: 16px;
  border: 1px solid var(--border-ultra-light);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.data-table th {
  background: var(--snow-soft);
  padding: 16px 20px;
  text-align: left;
  color: var(--text-muted);
  font-weight: 700;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.data-table td {
  padding: 20px;
  border-bottom: 1px solid var(--border-ultra-light);
  color: var(--text-main);
}

.data-table tr:last-child td {
  border-bottom: none;
}

.hash-col {
  font-family: monospace;
  color: var(--accent-blue);
  font-weight: 600;
}

.progress-bar {
  background: var(--snow-soft);
  height: 24px;
  border-radius: 12px;
  position: relative;
  overflow: hidden;
  width: 160px;
  border: 1px solid var(--border-ultra-light);
}

.progress-fill {
  background: linear-gradient(90deg, var(--accent-blue), #60A5FA);
  height: 100%;
  transition: width 0.6s cubic-bezier(0.23, 1, 0.32, 1);
}

.progress-text {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--text-main);
  font-weight: 800;
  text-shadow: 0 0 2px rgba(255, 255, 255, 0.5);
}

.status-tag {
  padding: 6px 12px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 800;
  text-transform: uppercase;
}

.status-tag.importing { background: var(--accent-soft); color: var(--accent-blue); }
.status-tag.done { background: #DCFCE7; color: #10B981; }
.status-tag.failed { background: #FEE2E2; color: #EF4444; }

.error-text { color: #EF4444; font-weight: 800; }
.time-col { color: var(--text-muted); font-size: 13px; }
.empty-text { text-align: center; padding: 64px; color: var(--text-muted); font-style: italic; }

/* [Phase 3] 可见度 / 部门编码 字段样式 */
.select-wrapper, .select-wrapper + div {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.dept-hint {
  font-size: 11px;
  color: #D97706;
  font-weight: 700;
  background: #FEF3C7;
  padding: 2px 8px;
  border-radius: 6px;
}
.select-input {
  appearance: none;
  cursor: pointer;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%236B7280' stroke-width='2.5'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 16px center;
  padding-right: 40px;
}
/* DEPT 模式下部门编码输入框高亮边框提示必填 */
.dept-required {
  border-color: #D97706 !important;
  box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.12) !important;
}
</style>
