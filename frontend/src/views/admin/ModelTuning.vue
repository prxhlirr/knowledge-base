<template>
  <div class="tuning-container">
    <header class="page-header">
      <h2>模型热调优面板</h2>
      <p class="subtitle">实时调整 AI 推理与检索融合参数，无需重启服务即可生效</p>
    </header>

    <div v-if="loading" class="loading-state">正在读取系统配置...</div>
    
    <div v-else class="tuning-content">
      <!-- 1. 推理引擎性能区 -->
      <section class="config-section">
        <h3 class="section-title">推理引擎与算力约束</h3>
        <div class="form-grid">
          <div class="form-item">
            <label>CPU 线程数</label>
            <input type="number" v-model="config.cpuThreads" min="1" max="32">
            <p class="hint">当回退到 CPU 推断时生效。一般建议设置为服务器核心数的 1~2 倍。GPU 环境下作为 ONNX 内置运算池。</p>
          </div>
        </div>
      </section>

      <!-- 2. 检索融合策略区 -->
      <section class="config-section">
        <h3 class="section-title">混合检索融合策略 (RRF/Weights)</h3>
        <div class="form-grid">
          <div class="form-item">
            <label>BM25 文本权重比 ({{ config.bm25Weight }})</label>
            <input type="range" v-model="config.bm25Weight" min="0" max="1" step="0.1">
            <p class="hint">由于字面匹配（关键字匹配）。适合精确查询文件名或专有名词。</p>
          </div>
          <div class="form-item">
            <label>Vector 向量权重比 ({{ config.vectorWeight }})</label>
            <input type="range" v-model="config.vectorWeight" min="0" max="1" step="0.1">
            <p class="hint">基于语义理解。适合模糊查找，即便关键词没有出现也能召回。</p>
          </div>
          <div class="form-item">
            <label>RRF 排序探测窗口</label>
            <input type="number" v-model="config.rrfWindowSize">
            <p class="hint">融合多路结果时的探测深度。<b>推荐值: 60</b>。</p>
          </div>
          <div class="form-item custom-checkbox">
            <label>
              <input type="checkbox" v-model="config.circuitBreakerEnabled"> 开启全局检索熔断 (降级为纯文本搜索)
            </label>
            <p class="hint" style="margin-left: 20px;">当 AI 模型服务异常或超时严重时启用，确保系统基本可用。</p>
          </div>
        </div>
      </section>

      <!-- 3. 高级算法精调 (New) -->
      <section class="config-section">
        <h3 class="section-title">高级算法精调 (黑盒参数显性化)</h3>
        <div class="form-grid">
          <div class="form-item">
            <label>标题增强权重 (Title Boost)</label>
            <input type="number" v-model="config.titleBoost" step="0.5" min="1">
            <p class="hint">控制标题匹配的优先级。<b>建议: 3-8</b>。值越大，标题中包含关键词的文档排名越靠前。</p>
          </div>
          <div class="form-item">
            <label>ES 分数归一化基准</label>
            <input type="number" v-model="config.esNormBase" step="1" min="1">
            <p class="hint">将全文检索原始分映射至 0-1 的分母。若结果分数过深导致无法区分，请尝试调大此值。</p>
          </div>
          <div class="form-item">
            <label>精排文档截断数量 (Limit)</label>
            <input type="number" v-model="config.rerankLimit" min="1" max="100">
            <p class="hint">限制送往 AI 深度重排的文档数。数量越多结果越准，但响应延迟越明显。</p>
          </div>
          <div class="form-item">
            <label>精排单文截断字符数</label>
            <input type="number" v-model="config.rerankMaxChars" step="100" min="100">
            <p class="hint">单篇文档送审精排的最长字符数。调小此值可显著降低高并发下的网络吞吐压力。</p>
          </div>
        </div>
      </section>

      <!-- 4. 微服务通信超时防线 -->
      <section class="config-section">
        <h3 class="section-title">通信超时与死锁防线 (Timeouts)</h3>
        <div class="form-grid">
          <div class="form-item">
            <label>ES 底座检索超时封锁 (ms)</label>
            <input type="number" v-model="config.esQueryTimeout" step="100" min="500">
            <p class="hint">全文与 KNN 混合召回的底层超时警戒线。值越短死锁恢复越快。<b>默认: 1500</b></p>
          </div>
          <div class="form-item">
            <label>Embedding 向量化接口超时 (ms)</label>
            <input type="number" v-model="config.embeddingTimeout" step="100" min="500">
            <p class="hint">抽取问题语义向量时的发包握手超时。<b>默认: 1500</b></p>
          </div>
          <div class="form-item">
            <label>Rerank 高深重排交叉比对超时 (ms)</label>
            <input type="number" v-model="config.rerankTimeout" step="100" min="500">
            <p class="hint">大模型二次重排由于极耗显存且易卡死，设置合理的生命线。<b>默认: 1500</b></p>
          </div>
        </div>
      </section>

      <!-- 5. 智能短路与极限阈值 -->
      <section class="config-section">
        <h3 class="section-title">智能短路与豁免阈值 (Breakers)</h3>
        <div class="form-grid">
          <div class="form-item">
            <label>纯词汇提速通道拦截长度</label>
            <input type="number" v-model="config.lexicalFastPathMaxLength" min="1" max="5">
            <p class="hint">搜索语短于此（如单双字词），绝不惊扰向量计算，强制走极速通道。<b>默认: 2</b></p>
          </div>
          <div class="form-item">
            <label>空泛烂词免排阈值</label>
            <input type="number" v-model="config.adaptiveBreakerMaxLength" min="1" max="5">
            <p class="hint">即便通过向量打回一波散乱文档，由于其搜索太短而判断无需深度重排，一律拦截。<b>默认: 2</b></p>
          </div>
          <div class="form-item">
            <label>底线放弃标准: 最低综合 RRF 分</label>
            <input type="number" v-model="config.breakerRrfThreshold" step="0.005" min="0">
            <p class="hint">双轨召回并榜后，若金牌得分低于此值，判定库内查无此物，跳过重排节约显存。<b>默认: 0.020</b></p>
          </div>
          <div class="form-item">
            <label>底线放弃标准: 最低 ES 原始分</label>
            <input type="number" v-model="config.breakerRawScoreThreshold" step="0.5" min="0">
            <p class="hint">RRF并不会判定共现性，用此值把控字词交集的绝对底线。<b>默认: 1.0</b></p>
          </div>
          <div class="form-item" style="grid-column: span 2;">
            <label>高分碾压直通车 (High-Score Exemption)</label>
            <input type="number" v-model="config.highScoreExemptionThreshold" step="0.5" min="5.0">
            <p class="hint"><b>核武器级重排豁免：</b>某篇文档的词元命中率高到爆炸（如 ES 原始分数 >12.0），直接颁发免检金牌不再提交大模型重新校准，斩断高达几秒的延迟。<b>推荐: 12.0</b></p>
          </div>
        </div>
      </section>

      <!-- 6. 公文多规制语义分片参数矩阵 (Semantic Chunking) -->
      <section class="config-section">
        <h3 class="section-title">公文多规制语义分片参数矩阵 (Semantic Chunking)</h3>
        <p class="hint" style="margin-bottom: 15px;">动态调控知识库入库时的篇章解构粒度与向量特征融合率。调整后对所有新上传件立即生效。</p>
        <div class="form-grid">
          <div class="form-item">
            <label>安全切分硬上限 (Max: {{ config.maxChunkSize }})</label>
            <input type="range" v-model="config.maxChunkSize" min="300" max="1500" step="50">
            <p class="hint">单段落最长容忍度，一旦超过无论是否破坏语境强制斩断。</p>
          </div>
          <div class="form-item">
            <label>目标切分游标 (Target: {{ config.targetChunkSize }})</label>
            <input type="range" v-model="config.targetChunkSize" min="200" max="800" step="50">
            <p class="hint">切块算法在寻找标点安全断句时，试图达成的最理想字频数。</p>
          </div>
          <div class="form-item">
            <label>零碎块合并下限 (Min: {{ config.minChunkSize }})</label>
            <input type="range" v-model="config.minChunkSize" min="50" max="250" step="10">
            <p class="hint">短于此字数的碎块会被强制向上或向下吸附至主块，消除孤岛。</p>
          </div>
          <div class="form-item">
            <label>上下文自愈重叠区 (Overlap: {{ config.overlapSize }})</label>
            <input type="range" v-model="config.overlapSize" min="0" max="200" step="10">
            <p class="hint">跨块时智能寻标点回退的重叠字数窗口，防止首尾语义割裂。</p>
          </div>
          <div class="form-item">
            <label>降级滑动窗口法底座尺寸</label>
            <input type="number" v-model="config.slidingWindowSize" step="50" min="100">
            <p class="hint">无结构纯文本触发暴力切分时的滑窗大小。步长通常与此同步 (-50)。</p>
          </div>
          <div class="form-item">
            <label>最低入库容忍质量分 (Quality)</label>
            <input type="number" v-model="config.minQualityScore" step="0.1" min="0" max="1">
            <p class="hint">自动剔除由表格乱码符号或全数字回车组成的垃圾块。<b>通常为 0.3</b></p>
          </div>
        </div>
      </section>

      <!-- 7. 公文智能抓取规则池 (Regex EAV) -->
      <section class="config-section">
        <h3 class="section-title">公文智能抓取规则池 (Regex EAV)</h3>
        <p class="hint" style="margin-bottom: 5px;">通过高容错正则表达式动态抽取公文头部特征入库，支持热重载。</p>
        <div class="info-alert">
          <p><b>💡 使用向导：</b></p>
          <ul>
            <li><b>字段标识：</b>英文键名，入库至 ES 字典，例如：<code>theme_classification</code></li>
            <li><b>显示名称：</b>中文标注，供人阅读识别，例如：<code>主题分类</code></li>
            <li><b>安全正则：</b>请确保正则中包含唯一一个捕获组 <code>()</code> 来圈出所需值。为防止 CPU 死锁，底座引擎强制切断扫描范围（仅限文章首部1500字符）。<br/>系统已默认载入公文新国标 7 大特征抓取法作为预置规则供您参考。</li>
          </ul>
        </div>
        <div class="rules-container">
          <div v-for="(rule, index) in config.metaExtractRules" :key="index" class="rule-row">
            <input type="text" v-model="rule.key" placeholder="字段标识 (如 theme_classification)" class="rule-input">
            <input type="text" v-model="rule.label" placeholder="显示名称 (如 主题分类)" class="rule-input">
            <input type="text" v-model="rule.regex" placeholder="安全正则 (如 主\s*题\s*分\s*类\s*[:：]\s*([^\n\r]+))" class="rule-input regex-input">
            <button class="btn-danger" @click="removeRule(index)">删除</button>
          </div>
          <button class="btn-secondary add-btn" @click="addRule">➕ 添加抓取特征列</button>
        </div>
      </section>

      <!-- 8. 模型路径管理 -->
      <section class="config-section">
        <h3 class="section-title">底层模型存储路径</h3>
        <div class="form-full">
          <div class="form-item">
            <label>Embedding 模型绝对路径</label>
            <input type="text" v-model="config.modelPath" class="full-input">
          </div>
          <div class="form-item" style="margin-top: 15px;">
            <label>Reranker 模型绝对路径</label>
            <input type="text" v-model="config.rerankerPath" class="full-input">
          </div>
        </div>
      </section>

      <div class="action-bar">
        <button class="btn-secondary" @click="fetchConfig">重置</button>
        <button class="btn-primary" :disabled="saving" @click="saveConfig">
          {{ saving ? '正在下发配置...' : '保存并立即应用调优' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';
import { API_BASE } from '../../utils/apiBase';

const config = ref({});
const loading = ref(true);
const saving = ref(false);

const addRule = () => {
  if (!config.value.metaExtractRules) config.value.metaExtractRules = [];
  config.value.metaExtractRules.push({ key: '', label: '', regex: '' });
};

const removeRule = (index) => {
  config.value.metaExtractRules.splice(index, 1);
};

const fetchConfig = async () => {
  loading.value = true;
  try {
    const res = await axios.get(`${API_BASE}/admin/tuning/config`);
    if (res.data.code === 200) {
      config.value = res.data.data;
      if (typeof config.value.metaExtractRules === 'string') {
        try { config.value.metaExtractRules = JSON.parse(config.value.metaExtractRules); } 
        catch (e) { config.value.metaExtractRules = []; }
      }
      if (!config.value.metaExtractRules) config.value.metaExtractRules = [];
    }
  } catch (err) {
    alert('获取配置失败: ' + err.message);
  } finally {
    loading.value = false;
  }
};

const saveConfig = async () => {
  saving.value = true;
  try {
    const payload = { ...config.value };
    if (Array.isArray(payload.metaExtractRules)) {
       payload.metaExtractRules = JSON.stringify(payload.metaExtractRules);
    }
    const res = await axios.post(`${API_BASE}/admin/tuning/config`, payload);
    if (res.data.code === 200) {
      alert('所有调优参数已持久化并同步至 AI 节点感知！');
      await fetchConfig();
    }
  } catch (err) {
    alert('应用配置失败: ' + err.message);
  } finally {
    saving.value = false;
  }
};

onMounted(fetchConfig);
</script>

<style scoped>
.tuning-container {
  max-width: 1000px;
  margin: 0 auto;
  animation: fadeIn 0.8s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.page-header {
  margin-bottom: 48px;
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

.tuning-content {
  display: flex;
  flex-direction: column;
  gap: 32px;
}

.config-section {
  background: var(--snow-bg);
  padding: 40px;
  border-radius: 24px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-md);
  transition: var(--transition-smooth);
}

.config-section:hover {
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

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px 48px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-item label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}

.form-item input[type="number"],
.form-item input[type="text"],
.form-item select {
  padding: 12px 16px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: var(--snow-soft);
  color: var(--text-main);
  font-size: 14px;
  transition: var(--transition-fast);
  outline: none;
}

.form-item input:focus,
.form-item select:focus {
  border-color: var(--accent-blue);
  background: var(--snow-bg);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.6;
  margin: 0;
}

.hint b {
  color: var(--accent-blue);
}

.slider-box {
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--snow-soft);
  padding: 8px 16px;
  border-radius: 12px;
}

.slider-box input[type="range"] {
  flex: 1;
  height: 4px;
  background: var(--border-light);
  border-radius: 2px;
  appearance: none;
}

.slider-box input[type="range"]::-webkit-slider-thumb {
  appearance: none;
  width: 18px;
  height: 18px;
  background: var(--accent-blue);
  border: 4px solid var(--snow-bg);
  border-radius: 50%;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
}

.slider-box span {
  font-size: 13px;
  font-weight: 700;
  color: var(--accent-blue);
  min-width: 80px;
  text-align: right;
}

.custom-checkbox {
  grid-column: span 2;
  background: var(--accent-soft);
  padding: 20px;
  border-radius: 16px;
  flex-direction: row;
  align-items: center;
  gap: 16px;
}

.custom-checkbox input {
  width: 20px;
  height: 20px;
  accent-color: var(--accent-blue);
}

.form-full {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.full-input {
  width: 100%;
  font-family: monospace;
}

.action-bar {
  position: sticky;
  bottom: 0px;
  background: var(--glass-white);
  backdrop-filter: var(--glass-blur);
  padding: 24px 0;
  display: flex;
  justify-content: flex-end;
  gap: 16px;
  margin-top: 48px;
  border-top: 1px solid var(--border-ultra-light);
  z-index: 100;
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

.btn-secondary {
  padding: 14px 32px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--snow-bg);
  color: var(--text-main);
  font-size: 15px;
  font-weight: 600;
  transition: var(--transition-smooth);
}

.btn-secondary:hover {
  background: var(--snow-soft);
}

.loading-state {
  padding: 100px;
  text-align: center;
  color: var(--text-muted);
  font-size: 18px;
  font-weight: 500;
}

/* 动态正则说明气泡与提取区样式 */
.info-alert {
  background-color: var(--snow-soft);
  border-left: 4px solid var(--accent-blue);
  padding: 12px 16px;
  border-radius: 4px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--text-main);
}
.info-alert p {
  margin: 0 0 8px 0;
}
.info-alert ul {
  margin: 0;
  padding-left: 20px;
}
.info-alert li {
  margin-bottom: 6px;
}
.info-alert code {
  background-color: var(--snow-bg);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: monospace;
  color: var(--accent-blue);
}

.rules-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rule-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.rule-input {
  padding: 12px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  font-size: 14px;
}
.rule-input:focus {
  border-color: var(--accent-blue);
  outline: none;
  box-shadow: 0 0 0 2px var(--accent-soft);
}
.rule-input:nth-child(1) { flex: 1; }
.rule-input:nth-child(2) { flex: 1; }
.rule-input.regex-input {
  flex: 2;
  font-family: monospace;
  background: var(--snow-soft);
}
.btn-danger {
  padding: 12px 16px;
  background-color: var(--status-error);
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  transition: var(--transition-smooth);
}
.btn-danger:hover {
  background-color: #ef4444; 
  transform: translateY(-1px);
}
.add-btn {
  align-self: flex-start;
  margin-top: 8px;
}
</style>
