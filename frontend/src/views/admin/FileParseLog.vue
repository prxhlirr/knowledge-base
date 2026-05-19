<template>
  <div class="logs-container fade-in">
    <div class="page-header">
      <h2>解析与入库审计日志</h2>
      <p class="subtitle">全生命周期追踪文档提取、语义切片与向量入库的健康状态</p>
    </div>

    <!-- 顶栏过滤器 -->
    <div class="filter-bar admin-card">
      <div class="filter-group">
        <label>流转状态</label>
        <select v-model="queryParams.status" @change="fetchData">
          <option :value="null">【全部状态】</option>
          <option :value="0">0 - 解析入库中</option>
          <option :value="1">1 - 入库成功</option>
          <option :value="2">2 - 提取解析异常</option>
          <option :value="3">3 - 入库分块异常</option>
          <option :value="4">4 - 超时中断</option>
        </select>
      </div>
      
      <div class="filter-group search-group">
        <label>全局检索</label>
        <div class="search-input-wrapper">
          <input 
            type="text" 
            v-model="queryParams.keyword" 
            placeholder="搜索文件路径、上传人..." 
            @keyup.enter="fetchData" 
          />
          <button class="btn-icon" @click="fetchData" title="搜索">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="table-container admin-card">
      <table class="data-table">
        <thead>
          <tr>
            <th width="15%">源文件</th>
            <th width="10%">流转节点</th>
            <th width="10%">上传权属</th>
            <th width="12%">时间打点</th>
            <th width="12%">解析/切片耗时</th>
            <th width="8%">切片总产出</th>
            <th width="18%">底报回执</th>
            <th width="15%">修复权</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="8" class="empty-cell">加载中...</td></tr>
          <tr v-else-if="tableData.length === 0"><td colspan="8" class="empty-cell">暂无流转日志记录</td></tr>
          <tr v-for="row in tableData" :key="row.id" class="table-row">
            <td>
              <div class="file-name" :title="row.filePath">{{ getFilename(row.filePath) }}</div>
              <div class="file-code">ID: {{ truncateUUID(row.fileCode) }}</div>
            </td>
            <td>
               <span class="status-badge" :class="getStatusClass(row.status)">
                 {{ getStatusLabel(row.status) }}
               </span>
            </td>
            <td>
              <div class="user-info">{{ row.uploader || '佚名' }}</div>
              <div class="dept-info">{{ row.uploaderDept || '未知单位' }}</div>
            </td>
            <td>
              <div class="time-label">上: {{ formatDate(row.uploadTime) }}</div>
              <div class="time-label" v-if="row.indexTime">入: {{ formatDate(row.indexTime) }}</div>
            </td>
            <td>
              <div class="stats-pill" v-if="row.parseDurationMs">
                提取: {{ row.parseDurationMs }}ms
              </div>
              <div class="stats-pill alt" v-if="row.chunkDurationMs">
                入库: {{ row.chunkDurationMs }}ms
              </div>
            </td>
            <td class="chunk-count">{{ row.chunkCount || 0 }} 块</td>
            <td class="log-cell">
              <div class="log-text" :title="row.parseResult" :class="{'log-error': row.status > 1}">
                {{ row.parseResult || (row.status === 1 ? '健康无损' : '流转中...') }}
              </div>
            </td>
            <td>
              <div class="action-buttons">
                <!-- 仅当发生拦截或超时时，允许一键挽救 -->
                <button 
                  v-if="row.status === 2 || row.status === 3 || row.status === 4" 
                  class="btn-retry" 
                  @click="handleSoftRetry(row.fileCode)">
                  软重试
                </button>
                <button 
                  v-if="row.status === 2 || row.status === 3 || row.status === 4 || row.parseResult" 
                  class="btn-replace" 
                  @click="triggerHardReplace(row)">
                  源覆盖
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 底部分页 -->
      <div class="pagination-bar" v-if="total > 0">
        <span class="page-info">共找到 {{ total }} 条记录，当前 {{ queryParams.current }}/{{ Math.ceil(total / queryParams.size) }} 页</span>
        <div class="page-controls">
          <button :disabled="queryParams.current <= 1" @click="changePage(queryParams.current - 1)">上一页</button>
          <button :disabled="queryParams.current * queryParams.size >= total" @click="changePage(queryParams.current + 1)">下一页</button>
        </div>
      </div>
    </div>

    <!-- 覆盖文件弹窗 Modal -->
    <div class="modal-overlay" v-if="showReplaceModal" @click.self="showReplaceModal = false">
      <div class="modal-content admin-card pop-in">
        <div class="modal-header">
          <h3>文件损伤掩盖级修复</h3>
          <button class="btn-close" @click="showReplaceModal = false">&times;</button>
        </div>
        <div class="modal-body">
          <p class="warning-text">⚠️ 警告：这将使用最新文件顶替旧损毁记录（FileCode不失效），并将先无条件斩除 ES 中所有遗像向量，随后再次推入等待队列重新解析。</p>
          
          <div class="form-group">
            <label>待修复目标ID (不可篡改)</label>
            <input type="text" :value="replaceForm.fileCode" disabled />
          </div>
          <div class="form-group">
            <label>新文件装载</label>
            <input type="file" ref="fileInput" @change="onFileSelected" />
            <span class="file-hint" v-if="selectedFile">已选定: {{ selectedFile.name }}</span>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showReplaceModal = false">取消</button>
          <button class="btn-primary" :disabled="!selectedFile || isReplacing" @click="submitHardReplace">
            {{ isReplacing ? '强行挂载中...' : '提交硬修复' }}
          </button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const tableData = ref([]);
const total = ref(0);
const loading = ref(false);

const queryParams = ref({
  current: 1,
  size: 15,
  status: null,
  keyword: ''
});

// 重试覆盖上下文
const showReplaceModal = ref(false);
const isReplacing = ref(false);
const replaceForm = ref({ fileCode: '' });
const selectedFile = ref(null);
const fileInput = ref(null);

const fetchData = async () => {
  loading.value = true;
  try {
    let url = `/api/v1/admin/logs/page?current=${queryParams.value.current}&size=${queryParams.value.size}`;
    if (queryParams.value.status !== null) {
      url += `&status=${queryParams.value.status}`;
    }
    if (queryParams.value.keyword) {
      url += `&keyword=${encodeURIComponent(queryParams.value.keyword)}`;
    }
    const res = await fetch(url).then(r => r.json());
    if (res.code === 200 && res.data) {
      tableData.value = res.data.records || [];
      total.value = res.data.total || 0;
    }
  } catch (err) {
    console.error("Failed to fetch logs", err);
  } finally {
    loading.value = false;
  }
};

const changePage = (page) => {
  queryParams.value.current = page;
  fetchData();
};

const handleSoftRetry = async (fileCode) => {
  if (!confirm('确定要在不修改文件物理实体的前提下，将此任务打回 Redis 重新排队吗？（此举将触发 Delete By Query）')) return;
  
  try {
    const res = await fetch('/api/v1/admin/logs/retry', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileCode })
    }).then(r => r.json());
    
    if (res.code === 200) {
      alert("软重试指令已送达队列！");
      fetchData();
    } else {
      alert("错误：" + res.msg);
    }
  } catch (e) {
    alert("网络异常: " + e);
  }
};

const triggerHardReplace = (row) => {
  replaceForm.value.fileCode = row.fileCode;
  selectedFile.value = null;
  showReplaceModal.value = true;
};

const onFileSelected = (e) => {
  const file = e.target.files[0];
  if (file) {
    selectedFile.value = file;
  }
};

const submitHardReplace = async () => {
  if (!selectedFile.value) return;
  
  isReplacing.value = true;
  const formData = new FormData();
  formData.append("file", selectedFile.value);
  formData.append("fileCode", replaceForm.value.fileCode);
  
  try {
    const res = await fetch('/api/v1/admin/logs/hard_replace', {
      method: 'POST',
      body: formData
    }).then(r => r.json());
    
    if (res.code === 200) {
      alert("已强制挂载新物理文件并通过幂等审计入列！");
      showReplaceModal.value = false;
      fetchData();
    } else {
      alert("覆盖失败：" + res.msg);
    }
  } catch (e) {
    alert("上传网络断联：" + e);
  } finally {
    isReplacing.value = false;
  }
};

// 工具函数
const getFilename = (path) => {
  if (!path) return 'UnKnown';
  return path.split(/[\/\\]/).pop();
};

const truncateUUID = (uuid) => {
  if (!uuid) return '';
  return `${uuid.substring(0, 8)}...${uuid.substring(uuid.length - 4)}`;
};

const getStatusLabel = (status) => {
  const map = {
    0: '解析流转中',
    1: '绿灯放行',
    2: '底物提取异常',
    3: '高维分块阻断',
    4: '脑死熔断挂起'
  };
  return map[status] || '未知位态';
};

const getStatusClass = (status) => {
  const map = {
    0: 'status-blue',
    1: 'status-green',
    2: 'status-orange',
    3: 'status-red',
    4: 'status-gray'
  };
  return map[status] || '';
};

const formatDate = (dateStr) => {
  if (!dateStr) return '';
  const d = new Date(dateStr);
  return `${d.getMonth()+1}-${d.getDate()} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`;
};

onMounted(() => {
  fetchData();
});
</script>

<style scoped>
.logs-container {
  padding: 32px 48px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 32px;
}
.page-header h2 {
  font-size: 26px;
  font-weight: 800;
  margin: 0 0 8px 0;
  color: #111827;
  letter-spacing: -0.5px;
}
.page-header .subtitle {
  color: #6B7280;
  margin: 0;
  font-size: 15px;
}

.admin-card {
  background: #ffffff;
  border-radius: 16px;
  border: 1px solid rgba(0,0,0,0.06);
  box-shadow: 0 4px 20px rgba(0,0,0,0.03);
  margin-bottom: 24px;
}

.filter-bar {
  padding: 20px 24px;
  display: flex;
  gap: 24px;
  align-items: flex-end;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flex-1 { flex: 1; }

.filter-group label {
  font-size: 13px;
  font-weight: 600;
  color: #4B5563;
}

select, input[type="text"] {
  height: 40px;
  padding: 0 16px;
  border-radius: 8px;
  border: 1px solid #D1D5DB;
  background: #F9FAFB;
  font-size: 14px;
  color: #1F2937;
  transition: all 0.2s;
  outline: none;
}
select:focus, input[type="text"]:focus {
  border-color: #3B82F6;
  background: #FFFFFF;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.search-input-wrapper {
  display: flex;
  width: 100%;
  position: relative;
}
.search-input-wrapper input {
  width: 100%;
  padding-right: 44px;
}
.btn-icon {
  position: absolute;
  right: 4px;
  top: 4px;
  height: 32px;
  width: 32px;
  border: none;
  background: transparent;
  color: #6B7280;
  border-radius: 6px;
  cursor: pointer;
}
.btn-icon:hover {
  background: #E5E7EB;
  color: #111827;
}

.btn-primary {
  height: 40px;
  padding: 0 24px;
  background: linear-gradient(135deg, #1A56DB, #2563EB);
  color: white;
  border: none;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.25);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.table-container {
  overflow: hidden;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
.data-table th {
  padding: 16px 24px;
  font-size: 13px;
  font-weight: 700;
  color: #6B7280;
  border-bottom: 1px solid #E5E7EB;
  background: #F9FAFB;
  text-transform: uppercase;
}
.data-table td {
  padding: 16px 24px;
  border-bottom: 1px solid #F3F4F6;
  vertical-align: middle;
}
.table-row:hover td {
  background: #F9FAFB;
}

.empty-cell {
  text-align: center;
  padding: 64px !important;
  color: #9CA3AF;
}

.file-name {
  font-weight: 600;
  color: #111827;
  font-size: 14px;
  margin-bottom: 4px;
}
.file-code {
  font-size: 12px;
  color: #9CA3AF;
  font-family: monospace;
}

.status-badge {
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 600;
  display: inline-block;
}
.status-blue { background: #DBEAFE; color: #1D4ED8; }
.status-green { background: #D1FAE5; color: #047857; }
.status-orange { background: #FEF3C7; color: #B45309; }
.status-red { background: #FEE2E2; color: #B91C1C; }
.status-gray { background: #F3F4F6; color: #4B5563; }

.user-info { font-weight: 500; font-size: 13px; }
.dept-info { font-size: 12px; color: #6B7280; }

.time-label { font-size: 12px; color: #4B5563; margin-bottom: 2px; }

.stats-pill {
  font-size: 11px;
  padding: 2px 6px;
  background: #EFF6FF;
  color: #2563EB;
  border-radius: 4px;
  display: inline-block;
  margin-bottom: 4px;
  font-family: monospace;
}
.stats-pill.alt { background: #F5F3FF; color: #7C3AED; }

.chunk-count { font-weight: 600; color: #111827; }

.log-cell { max-width: 200px; }
.log-text {
  font-size: 12px;
  color: #4B5563;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.log-error {
  color: #B91C1C;
  font-weight: 500;
}

.action-buttons {
  display: flex;
  gap: 8px;
}
.btn-retry, .btn-replace {
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-retry {
  background: #FFFBEB;
  color: #D97706;
  border: 1px solid #FDE68A;
}
.btn-retry:hover { background: #FEF3C7; }
.btn-replace {
  background: #FEF2F2;
  color: #DC2626;
  border: 1px solid #FECACA;
}
.btn-replace:hover { background: #FEE2E2; }

.pagination-bar {
  padding: 16px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #E5E7EB;
  background: #F9FAFB;
}
.page-info { font-size: 13px; color: #6B7280; }
.page-controls { display: flex; gap: 8px; }
.page-controls button {
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid #D1D5DB;
  background: #FFFFFF;
  border-radius: 6px;
  cursor: pointer;
}
.page-controls button:hover:not(:disabled) { background: #F3F4F6; }
.page-controls button:disabled { opacity: 0.5; cursor: not-allowed; }

/* Modal 覆盖组件 */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal-content {
  width: 480px;
  padding: 0;
  overflow: hidden;
}
.modal-header {
  padding: 20px 24px;
  border-bottom: 1px solid #E5E7EB;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.modal-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #111827; }
.btn-close { background: none; border: none; font-size: 24px; color: #9CA3AF; cursor: pointer; }
.btn-close:hover { color: #111827; }
.modal-body { padding: 24px; }
.warning-text {
  font-size: 13px; color: #B91C1C; background: #FEF2F2; padding: 12px; border-radius: 8px; margin: 0 0 20px 0;
}
.modal-footer {
  padding: 16px 24px;
  background: #F9FAFB;
  border-top: 1px solid #E5E7EB;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.btn-cancel {
  padding: 8px 16px; background: #FFFFFF; border: 1px solid #D1D5DB; border-radius: 6px; font-weight: 500; cursor: pointer;
}

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes popIn { 0% { opacity: 0; transform: scale(0.95); } 100% { opacity: 1; transform: scale(1); } }
.fade-in { animation: fadeIn 0.4s ease; }
.pop-in { animation: popIn 0.3s cubic-bezier(0.16, 1, 0.3, 1); }
</style>
