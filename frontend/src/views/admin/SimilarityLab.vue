<template>
  <div class="similarity-lab-container">
    <header class="page-header">
      <h2>向量相似度探针 (Similarity Lab)</h2>
      <p class="subtitle">直观验证两段文本在 BGE-M3 向量空间中的距离，或对全库进行相似度分布扫描</p>
    </header>

    <div class="tabs">
      <button :class="['tab-btn', { active: activeTab === 'compare' }]" @click="activeTab = 'compare'">双文本对比</button>
      <button :class="['tab-btn', { active: activeTab === 'scan' }]" @click="activeTab = 'scan'">全库相似度扫描</button>
    </div>

    <!-- Tab 1: 双文本对比 -->
    <div v-if="activeTab === 'compare'" class="tab-content">
      <div class="compare-layout">
        <div class="input-panel">
          <div class="text-box-wrapper">
            <label>文本 A</label>
            <textarea v-model="compareForm.textA" placeholder="在这里输入第一段文本..."></textarea>
          </div>
          <div class="vs-badge">VS</div>
          <div class="text-box-wrapper">
            <label>文本 B</label>
            <textarea v-model="compareForm.textB" placeholder="在这里输入第二段文本..."></textarea>
          </div>
          <button class="btn-primary run-btn" :disabled="comparing" @click="runCompare">
            {{ comparing ? '计算中...' : '开始对比' }}
          </button>
        </div>

        <div class="result-panel">
          <h3>分析结果</h3>
          <div v-if="compareResult" class="result-card">
            <div class="meter-container">
              <svg viewBox="0 0 200 100" class="gauge">
                <path class="gauge-bg" d="M 20 100 A 80 80 0 0 1 180 100" />
                <path class="gauge-fill" :d="gaugePath" :stroke="colorForCosine(compareResult.cosine)" />
              </svg>
              <div class="score-display">
                <span class="score-value" :style="{ color: colorForCosine(compareResult.cosine) }">
                  {{ (compareResult.cosine).toFixed(4) }}
                </span>
                <span class="score-label">余弦相似度</span>
              </div>
            </div>
            
            <div class="detail-row">
              <span class="label">语义评估:</span>
              <span class="value badge" :style="{ backgroundColor: colorForCosine(compareResult.cosine, 0.1), color: colorForCosine(compareResult.cosine) }">
                {{ compareResult.label }}
              </span>
            </div>
            <div class="detail-row">
              <span class="label">推断耗时:</span>
              <span class="value">{{ compareResult.costMs }} ms</span>
            </div>
          </div>
          <div v-else class="empty-state">
            <p>输入两段文本并点击“开始对比”查看结果</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Tab 2: 全库扫描 -->
    <div v-if="activeTab === 'scan'" class="tab-content">
      <div class="scan-header">
        <div class="scan-input-group">
          <input type="text" v-model="scanForm.query" class="full-input" placeholder="输入要扫描的基准文本（例如：保密义务）" @keyup.enter="runScan">
          <button class="btn-primary" :disabled="scanning" @click="runScan">
            <span v-if="scanning" class="spinner"></span>
            {{ scanning ? '正在全库计算...' : '开始全局扫描' }}
          </button>
        </div>
        <p class="hint">注意：全库扫描会遍历所有文档进行推断，上限默认为 500 条。</p>
      </div>

      <div v-if="scanResult" class="scan-results-area">
        <div class="distribution-chart">
          <h3>全库相似度分布</h3>
          <div class="bars-container">
            <div v-for="bar in scanResult.distribution" :key="bar.range" class="bar-wrapper">
              <div class="bar-value">{{ bar.count }}</div>
              <div class="bar-fill" :style="{ height: getBarHeight(bar.count) + '%', backgroundColor: colorForRange(bar.range) }"></div>
              <div class="bar-label">{{ bar.range }}</div>
            </div>
          </div>
        </div>

        <div class="docs-list">
          <div class="list-header">
            <h3>最相似文档 Top-N</h3>
            <span class="meta-info">共扫描 {{ scanResult.total }} 条 | 耗时 {{ scanResult.costMs }}ms</span>
          </div>
          
          <div class="doc-cards">
            <div v-for="doc in scanResult.items" :key="doc.docId" class="doc-card">
              <div class="doc-header">
                <h4>{{ doc.title }}</h4>
                <div class="score-badge" :style="{ backgroundColor: colorForCosine(doc.cosine, 0.1), color: colorForCosine(doc.cosine) }">
                  {{ doc.cosine.toFixed(4) }} ({{ doc.label }})
                </div>
              </div>
              <p class="doc-chunk">{{ doc.chunkText }}</p>
            </div>
            <div v-if="scanResult.items.length === 0" class="empty-list">未扫描到文档</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import axios from 'axios';

const activeTab = ref('compare'); // 'compare' 或 'scan'

// --- 双文本对比逻辑 ---
const compareForm = ref({ textA: '', textB: '' });
const comparing = ref(false);
const compareResult = ref(null);

const runCompare = async () => {
  if (!compareForm.value.textA.trim() || !compareForm.value.textB.trim()) {
    alert('请输入完整的文本A和文本B');
    return;
  }
  comparing.value = true;
  compareResult.value = null;
  try {
    const res = await axios.post(`${import.meta.env.VITE_API_BASE_URL}/admin/similarity/compare`, {
      textA: compareForm.value.textA,
      textB: compareForm.value.textB
    });
    if (res.data.code === 200) {
      compareResult.value = res.data.data;
    } else {
      alert(res.data.msg);
    }
  } catch (err) {
    alert('比较失败: ' + err.message);
  } finally {
    comparing.value = false;
  }
};

// 仪表盘 SVG 路径计算（半圆）
const gaugePath = computed(() => {
  if (!compareResult.value) return '';
  // cosine 范围通常在 [-1, 1], 我们将其映射到进度，通常大多在 [0, 1] 甚至 [0.2, 1] 之间。
  // 简单处理：将 < 0 的视作 0
  const score = Math.max(0, compareResult.value.cosine); 
  const radius = 80;
  // 角度推算：从 180度 (左侧) 到 0度 (右侧)
  const angle = Math.PI - (score * Math.PI);
  const x = 100 + radius * Math.cos(angle);
  const y = 100 - radius * Math.sin(angle);
  // SVG Arc 命令: A rx ry x-axis-rotation large-arc-flag sweep-flag x y
  return `M 20 100 A ${radius} ${radius} 0 0 1 ${x} ${y}`;
});

// 统一根据 Cosine 分数决定颜色
const colorForCosine = (score, alpha = 1) => {
  if (score >= 0.85) return `rgba(16, 185, 129, ${alpha})`; // 绿
  if (score >= 0.65) return `rgba(59, 130, 246, ${alpha})`; // 蓝
  if (score >= 0.45) return `rgba(245, 158, 11, ${alpha})`; // 橙
  return `rgba(239, 68, 68, ${alpha})`; // 红
};


// --- 全库扫描逻辑 ---
const scanForm = ref({ query: '' });
const scanning = ref(false);
const scanResult = ref(null);

const runScan = async () => {
  if (!scanForm.value.query.trim()) {
    alert('请输入基准文本');
    return;
  }
  scanning.value = true;
  scanResult.value = null;
  try {
    const res = await axios.post(`${import.meta.env.VITE_API_BASE_URL}/admin/similarity/corpus-scan`, {
      queryText: scanForm.value.query,
      limit: 500 // 默认扫 500 条
    });
    if (res.data.code === 200) {
      scanResult.value = res.data.data;
    } else {
      alert(res.data.msg);
    }
  } catch (err) {
    alert('扫描失败: ' + err.message);
  } finally {
    scanning.value = false;
  }
};

const maxBarCount = computed(() => {
  if (!scanResult.value || !scanResult.value.distribution) return 1;
  return Math.max(...scanResult.value.distribution.map(d => d.count), 1);
});

const getBarHeight = (count) => {
  return (count / maxBarCount.value) * 100;
};

const colorForRange = (rangeName) => {
  if (rangeName === '0.8–1.0') return 'rgba(16, 185, 129, 0.8)';
  if (rangeName === '0.6–0.8') return 'rgba(59, 130, 246, 0.8)';
  if (rangeName === '0.4–0.6') return 'rgba(245, 158, 11, 0.8)';
  return 'rgba(239, 68, 68, 0.8)';
};
</script>

<style scoped>
.similarity-lab-container {
  max-width: 1200px;
  margin: 0 auto;
  animation: fadeIn 0.6s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.page-header {
  margin-bottom: 32px;
}
.page-header h2 {
  font-size: 32px;
  font-weight: 800;
  color: var(--text-main);
  margin: 0 0 10px 0;
}
.subtitle {
  color: var(--text-muted);
  font-size: 16px;
}

.tabs {
  display: flex;
  gap: 16px;
  margin-bottom: 32px;
  border-bottom: 1px solid var(--border-light);
  padding-bottom: 16px;
}
.tab-btn {
  padding: 10px 24px;
  background: transparent;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
}
.tab-btn:hover {
  background: var(--snow-bg);
  color: var(--text-main);
}
.tab-btn.active {
  background: var(--accent-blue);
  color: white;
  box-shadow: var(--shadow-sm);
}

.tab-content {
  background: var(--snow-bg);
  border-radius: 24px;
  padding: 40px;
  border: 1px solid var(--border-ultra-light);
  box-shadow: var(--shadow-sm);
}

/* Compare Layout */
.compare-layout {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 48px;
}

.input-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
  position: relative;
}
.text-box-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.text-box-wrapper label {
  font-weight: 600;
  color: var(--text-main);
}
.text-box-wrapper textarea {
  height: 160px;
  padding: 16px;
  border-radius: 12px;
  border: 1px solid var(--border-light);
  background: var(--snow-soft);
  resize: vertical;
  font-size: 15px;
  line-height: 1.5;
  transition: border-color 0.2s;
}
.text-box-wrapper textarea:focus {
  outline: none;
  border-color: var(--accent-blue);
  background: white;
}
.vs-badge {
  position: absolute;
  top: 175px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--accent-blue);
  color: white;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 14px;
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.3);
  z-index: 10;
  border: 3px solid var(--snow-bg);
}

.run-btn {
  margin-top: 16px;
  align-self: flex-end;
}

.result-panel {
  display: flex;
  flex-direction: column;
  background: var(--snow-soft);
  border-radius: 20px;
  padding: 32px;
  border: 1px solid var(--border-light);
}
.result-panel h3 {
  margin-top: 0;
  color: var(--text-main);
}
.empty-state {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-style: italic;
}

.result-card {
  display: flex;
  flex-direction: column;
  gap: 32px;
  align-items: center;
  margin-top: 24px;
}

/* 仪表盘 */
.meter-container {
  position: relative;
  width: 260px;
  height: 140px;
}
.gauge {
  width: 100%;
  height: 100%;
  overflow: visible;
}
.gauge-bg {
  fill: none;
  stroke: var(--border-light);
  stroke-width: 16;
  stroke-linecap: round;
}
.gauge-fill {
  fill: none;
  stroke-width: 16;
  stroke-linecap: round;
  transition: d 1s cubic-bezier(0.25, 1, 0.5, 1);
}
.score-display {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.score-value {
  font-size: 48px;
  font-weight: 900;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.score-label {
  font-size: 13px;
  color: var(--text-muted);
  font-weight: 600;
  text-transform: uppercase;
  margin-top: 4px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: 12px 0;
  border-bottom: 1px dashed var(--border-light);
}
.detail-row .label {
  font-weight: 600;
  color: var(--text-muted);
}
.badge {
  padding: 4px 12px;
  border-radius: 20px;
  font-weight: 700;
  font-size: 14px;
}

/* Scan Layout */
.scan-header {
  margin-bottom: 40px;
}
.scan-input-group {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}
.full-input {
  flex: 1;
  padding: 14px 20px;
  border-radius: 12px;
  border: 1px solid var(--border-light);
  font-size: 16px;
}
.full-input:focus {
  outline: none;
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.hint {
  color: var(--text-muted);
  font-size: 13px;
  margin: 0;
}

.scan-results-area {
  display: flex;
  flex-direction: column;
  gap: 48px;
}

/* 分布图 */
.distribution-chart {
  background: var(--snow-soft);
  padding: 24px;
  border-radius: 16px;
  border: 1px solid var(--border-light);
}
.bars-container {
  display: flex;
  justify-content: space-around;
  align-items: flex-end;
  height: 200px;
  margin-top: 24px;
  padding: 0 20px;
}
.bar-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  width: 60px;
  justify-content: flex-end;
}
.bar-fill {
  width: 40px;
  border-radius: 4px 4px 0 0;
  transition: height 0.5s ease-out;
  min-height: 4px;
}
.bar-label {
  margin-top: 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
}
.bar-value {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
  color: var(--text-main);
}

/* 结果列表 */
.list-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 24px;
  padding-bottom: 12px;
  border-bottom: 2px solid var(--border-light);
}
.list-header h3 {
  margin: 0;
}
.meta-info {
  color: var(--text-muted);
  font-size: 14px;
}
.doc-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.doc-card {
  padding: 24px;
  background: white;
  border-radius: 16px;
  border: 1px solid var(--border-light);
  box-shadow: 0 2px 8px rgba(0,0,0,0.02);
  transition: transform 0.2s;
}
.doc-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.05);
  border-color: var(--border-ultra-light);
}
.doc-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.doc-header h4 {
  margin: 0;
  font-size: 16px;
  color: var(--accent-blue);
  flex: 1;
  padding-right: 16px;
  line-height: 1.4;
}
.score-badge {
  padding: 6px 12px;
  border-radius: 8px;
  font-weight: 700;
  font-size: 13px;
  white-space: nowrap;
}
.doc-chunk {
  margin: 0;
  font-size: 14px;
  color: var(--text-main);
  line-height: 1.6;
  opacity: 0.8;
}

/* 公用按钮 */
.btn-primary {
  padding: 12px 24px;
  background: var(--accent-blue);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.2s;
}
.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.2);
}
.btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255,255,255,0.3);
  border-radius: 50%;
  border-top-color: white;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
