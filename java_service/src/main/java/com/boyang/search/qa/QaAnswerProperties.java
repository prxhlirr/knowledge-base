package com.boyang.search.qa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "qa.answer")
public class QaAnswerProperties {
    private int promptMaxChars = 6000;
    private int targetPromptChars = 5000;
    private int evidenceMaxChars = 900;
    private int neighborEvidenceMaxChars = 420;
    private int qaEvidenceMaxChars = 600;
    private int factDigestMaxChars = 800;
    private boolean includeFactDigest = true;
    private int minDocs = 2;
    private int maxDocs = 4;
    private int maxCitations = 6;
    private int maxTotalChunks = 16;
    private int maxChunksPerDoc = 6;
    private double minQaConfidence = 0.65;
    private double minCoverage = 0.12;
    private double materialConfidenceDivisor = 6.0;
    private String modelKey = "QA_LLM_MODEL";
    private double temperature = 0.2;
    private int maxTokens = 600;
    private boolean claimPlanEnabled = true;
    private String structuredMode = "auto";
    private List<String> structuredRiskIntents = new ArrayList<>(Arrays.asList(
            "CONDITION", "COUNT_OR_TIME", "LIST"));
    private int claimPlanMaxTokens = 1200;
    private int structuredMaxEvidenceRecords = 16;
    private int structuredTimeoutMs = 8000;
    private boolean verificationRetryEnabled = true;
    private int maxVerificationRetries = 1;
    private boolean highRiskStrictMode = true;
    private boolean allowPromptStageEsFetch = false;
    private int evidencePrefetchMaxDocs = 4;
    private int evidencePrefetchWindowBefore = 1;
    private int evidencePrefetchWindowAfter = 2;
    private int evidencePrefetchMaxChunks = 200;
    private int qaRecallWaitBudgetMs = 400;
    private int qaRecallTimeoutMs = 2000;
    private boolean qaRecallRequired = false;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
    private long streamTimeoutMs = 70000L;
    private Prompt prompt = new Prompt();
    private StructuredPrompt structuredPrompt = new StructuredPrompt();
    private RiskPolicy riskPolicy = new RiskPolicy();

    public int getPromptMaxChars() { return promptMaxChars; }
    public void setPromptMaxChars(int promptMaxChars) { this.promptMaxChars = promptMaxChars; }
    public int getTargetPromptChars() { return targetPromptChars; }
    public void setTargetPromptChars(int targetPromptChars) { this.targetPromptChars = targetPromptChars; }
    public int getEvidenceMaxChars() { return evidenceMaxChars; }
    public void setEvidenceMaxChars(int evidenceMaxChars) { this.evidenceMaxChars = evidenceMaxChars; }
    public int getNeighborEvidenceMaxChars() { return neighborEvidenceMaxChars; }
    public void setNeighborEvidenceMaxChars(int neighborEvidenceMaxChars) { this.neighborEvidenceMaxChars = neighborEvidenceMaxChars; }
    public int getQaEvidenceMaxChars() { return qaEvidenceMaxChars; }
    public void setQaEvidenceMaxChars(int qaEvidenceMaxChars) { this.qaEvidenceMaxChars = qaEvidenceMaxChars; }
    public int getFactDigestMaxChars() { return factDigestMaxChars; }
    public void setFactDigestMaxChars(int factDigestMaxChars) { this.factDigestMaxChars = factDigestMaxChars; }
    public boolean isIncludeFactDigest() { return includeFactDigest; }
    public void setIncludeFactDigest(boolean includeFactDigest) { this.includeFactDigest = includeFactDigest; }
    public int getMinDocs() { return minDocs; }
    public void setMinDocs(int minDocs) { this.minDocs = minDocs; }
    public int getMaxDocs() { return maxDocs; }
    public void setMaxDocs(int maxDocs) { this.maxDocs = maxDocs; }
    public int getMaxCitations() { return maxCitations; }
    public void setMaxCitations(int maxCitations) { this.maxCitations = maxCitations; }
    public int getMaxTotalChunks() { return maxTotalChunks; }
    public void setMaxTotalChunks(int maxTotalChunks) { this.maxTotalChunks = maxTotalChunks; }
    public int getMaxChunksPerDoc() { return maxChunksPerDoc; }
    public void setMaxChunksPerDoc(int maxChunksPerDoc) { this.maxChunksPerDoc = maxChunksPerDoc; }
    public double getMinQaConfidence() { return minQaConfidence; }
    public void setMinQaConfidence(double minQaConfidence) { this.minQaConfidence = minQaConfidence; }
    public double getMinCoverage() { return minCoverage; }
    public void setMinCoverage(double minCoverage) { this.minCoverage = minCoverage; }
    public double getMaterialConfidenceDivisor() { return materialConfidenceDivisor; }
    public void setMaterialConfidenceDivisor(double materialConfidenceDivisor) { this.materialConfidenceDivisor = materialConfidenceDivisor; }
    public String getModelKey() { return modelKey; }
    public void setModelKey(String modelKey) { this.modelKey = modelKey; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isClaimPlanEnabled() { return claimPlanEnabled; }
    public void setClaimPlanEnabled(boolean claimPlanEnabled) { this.claimPlanEnabled = claimPlanEnabled; }
    public String getStructuredMode() { return structuredMode; }
    public void setStructuredMode(String structuredMode) { this.structuredMode = structuredMode; }
    public List<String> getStructuredRiskIntents() { return structuredRiskIntents; }
    public void setStructuredRiskIntents(List<String> structuredRiskIntents) {
        this.structuredRiskIntents = structuredRiskIntents == null ? new ArrayList<>() : structuredRiskIntents;
    }
    public int getClaimPlanMaxTokens() { return claimPlanMaxTokens; }
    public void setClaimPlanMaxTokens(int claimPlanMaxTokens) { this.claimPlanMaxTokens = claimPlanMaxTokens; }
    public int getStructuredMaxEvidenceRecords() { return structuredMaxEvidenceRecords; }
    public void setStructuredMaxEvidenceRecords(int structuredMaxEvidenceRecords) { this.structuredMaxEvidenceRecords = structuredMaxEvidenceRecords; }
    public int getStructuredTimeoutMs() { return structuredTimeoutMs; }
    public void setStructuredTimeoutMs(int structuredTimeoutMs) { this.structuredTimeoutMs = structuredTimeoutMs; }
    public boolean isVerificationRetryEnabled() { return verificationRetryEnabled; }
    public void setVerificationRetryEnabled(boolean verificationRetryEnabled) { this.verificationRetryEnabled = verificationRetryEnabled; }
    public int getMaxVerificationRetries() { return maxVerificationRetries; }
    public void setMaxVerificationRetries(int maxVerificationRetries) { this.maxVerificationRetries = maxVerificationRetries; }
    public boolean isHighRiskStrictMode() { return highRiskStrictMode; }
    public void setHighRiskStrictMode(boolean highRiskStrictMode) { this.highRiskStrictMode = highRiskStrictMode; }
    public boolean isAllowPromptStageEsFetch() { return allowPromptStageEsFetch; }
    public void setAllowPromptStageEsFetch(boolean allowPromptStageEsFetch) { this.allowPromptStageEsFetch = allowPromptStageEsFetch; }
    public int getEvidencePrefetchMaxDocs() { return evidencePrefetchMaxDocs; }
    public void setEvidencePrefetchMaxDocs(int evidencePrefetchMaxDocs) { this.evidencePrefetchMaxDocs = evidencePrefetchMaxDocs; }
    public int getEvidencePrefetchWindowBefore() { return evidencePrefetchWindowBefore; }
    public void setEvidencePrefetchWindowBefore(int evidencePrefetchWindowBefore) { this.evidencePrefetchWindowBefore = evidencePrefetchWindowBefore; }
    public int getEvidencePrefetchWindowAfter() { return evidencePrefetchWindowAfter; }
    public void setEvidencePrefetchWindowAfter(int evidencePrefetchWindowAfter) { this.evidencePrefetchWindowAfter = evidencePrefetchWindowAfter; }
    public int getEvidencePrefetchMaxChunks() { return evidencePrefetchMaxChunks; }
    public void setEvidencePrefetchMaxChunks(int evidencePrefetchMaxChunks) { this.evidencePrefetchMaxChunks = evidencePrefetchMaxChunks; }
    public int getQaRecallWaitBudgetMs() { return qaRecallWaitBudgetMs; }
    public void setQaRecallWaitBudgetMs(int qaRecallWaitBudgetMs) { this.qaRecallWaitBudgetMs = qaRecallWaitBudgetMs; }
    public int getQaRecallTimeoutMs() { return qaRecallTimeoutMs; }
    public void setQaRecallTimeoutMs(int qaRecallTimeoutMs) { this.qaRecallTimeoutMs = qaRecallTimeoutMs; }
    public boolean isQaRecallRequired() { return qaRecallRequired; }
    public void setQaRecallRequired(boolean qaRecallRequired) { this.qaRecallRequired = qaRecallRequired; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public long getStreamTimeoutMs() { return streamTimeoutMs; }
    public void setStreamTimeoutMs(long streamTimeoutMs) { this.streamTimeoutMs = streamTimeoutMs; }
    public Prompt getPrompt() { return prompt; }
    public void setPrompt(Prompt prompt) { this.prompt = prompt == null ? new Prompt() : prompt; }
    public StructuredPrompt getStructuredPrompt() { return structuredPrompt; }
    public void setStructuredPrompt(StructuredPrompt structuredPrompt) {
        this.structuredPrompt = structuredPrompt == null ? new StructuredPrompt() : structuredPrompt;
    }
    public RiskPolicy getRiskPolicy() { return riskPolicy; }
    public void setRiskPolicy(RiskPolicy riskPolicy) { this.riskPolicy = riskPolicy == null ? new RiskPolicy() : riskPolicy; }

    public static class Prompt {
        private String system = "你是政务知识库问答助理。请遵循以下规则：";
        private List<String> systemRules = new ArrayList<>(Arrays.asList(
                "只能依据已检索到的文档材料回答，不得补充材料之外的事实。",
                "先直接回答用户关心的事，再按要点展开。",
                "避免模板化开头，不要逐句照搬原文。",
                "在不改变事实的前提下做归纳、合并和解释性改写。",
                "严格区分拟任、现任、已任命、已晋升等事实状态。",
                "每个关键结论、条件、范围、时间、数量或步骤后直接放文档级引用小标签，例如[1]。",
                "最终答案里的引用只能使用[1]、[2]这类方括号数字；不要输出“文档1”“资料1”“片段1”“分片1”作为引用。",
                "不要单独写引用、来源、参考等说明行。",
                "如果材料不足，说明未找到直接依据，并只概括最接近的信息。",
                "如果材料冲突，指出冲突并优先采用时间或版本更明确的材料。"));
        private String sufficientInstruction = "请直接回答。若是清单、流程、条件、数量或时间问题，请优先条目化。每个要点要做适度归纳和解释，引用小标签直接放在要点句末。";
        private String insufficientInstruction = "请不要编造答案。请用自然语言说明未找到直接依据，并列出材料中最接近但不足以回答的信息。引用仍放在相关句子末尾。";
        private String userTemplate = ""
                + "你需要完成一个政务知识库问答任务。\n"
                + "问题类型：{{intent}}\n"
                + "证据充分性：{{answerable}}\n\n"
                + "事实摘要（来自原文抽取，不是模型结论）：\n{{fact_digest}}\n\n"
                + "原文材料：\n{{knowledge}}\n"
                + "用户问题：\n{{query}}\n\n"
                + "回答要求：\n"
                + "1. 先直接回答问题，不要复述“根据资料”。\n"
                + "2. 只使用材料中能确认的事实；拟任、公示、候选、任命、现任必须严格区分。\n"
                + "3. 每个关键结论句末保留引用编号，如 [1]。\n"
                + "4. 抄送、附件说明、页眉页脚等低价值内容，除非用户明确询问，不要作为答案主体。\n"
                + "5. 不得自行统计或推断总人数、总次数、总金额等数字；只有原文明写该数字时才可回答。\n"
                + "6. 如果材料只是列出部分名单，不要说“共计N人”，应说“材料中列出的人员包括”。\n"
                + "7. 如果证据不足，直接说明缺少什么证据，并列出最接近的信息。\n"
                + "8. 重要：请先在 <think> 和 </think> 标签内部，逐步梳理并提取材料中的核心事实与你的推导规划，然后再在标签外部输出最终面向用户的精简中文答案。\n\n"
                + "{{task_instruction}}";

        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public List<String> getSystemRules() { return systemRules; }
        public void setSystemRules(List<String> systemRules) { this.systemRules = systemRules == null ? new ArrayList<>() : systemRules; }
        public String getSufficientInstruction() { return sufficientInstruction; }
        public void setSufficientInstruction(String sufficientInstruction) { this.sufficientInstruction = sufficientInstruction; }
        public String getInsufficientInstruction() { return insufficientInstruction; }
        public void setInsufficientInstruction(String insufficientInstruction) { this.insufficientInstruction = insufficientInstruction; }
        public String getUserTemplate() { return userTemplate; }
        public void setUserTemplate(String userTemplate) {
            if (userTemplate != null && !userTemplate.trim().isEmpty()) {
                this.userTemplate = userTemplate;
            }
        }
    }

    public static class StructuredPrompt {
        private String factExtractorSystem = "你是知识库问答的事实抽取器。只从给定证据中抽取可审计事实，返回严格 JSON，不要直接回答用户。";
        private String factExtractorUserTemplate = "请从证据中抽取事实。JSON schema: "
                + "{\"facts\":[{\"fact_id\":\"F1\",\"type\":\"general|person_position_event|condition|procedure|quantity|time|policy\","
                + "\"subject\":\"\",\"predicate\":\"\",\"object\":\"\",\"status\":\"PROPOSED|CANDIDATE|PUBLIC_NOTICE|APPOINTED|CURRENT|REMOVED|HISTORICAL|UNKNOWN\","
                + "\"time\":\"\",\"quantity\":\"\",\"text\":\"短事实\",\"evidence_id\":\"E1\",\"citation_index\":1,\"risk_tags\":[]}],\"warnings\":[]}。\n"
                + "{{payload}}";
        private String claimPlanSystem = "你是知识库问答的结论规划器。只依据已抽取事实生成可审计 claim_plan，返回严格 JSON，不要引入事实外的人名、时间、数量、状态或结论。";
        private String claimPlanUserTemplate = "请生成 claim_plan。JSON schema: "
                + "{\"answerable\":true,\"direct_answer\":\"\",\"claims\":[{\"claim_id\":\"C1\","
                + "\"claim_text\":\"简短结论\",\"supporting_fact_ids\":[\"F1\"],"
                + "\"citation_indexes\":[1],\"risk_level\":\"normal|high\",\"answerable\":true}],"
                + "\"missing_evidence\":[],\"conflicts\":[]}。\n"
                + "{{payload}}";
        private String renderSystem = "你是知识库问答渲染器。只能依据已审核的 claim_plan 生成最终中文答案；不要新增实体、日期、数量、状态判断、原因或结论；不要输出 user、assistant、system 等角色标签。";
        private String renderUserTemplate = "请根据已审核的 claim_plan 生成简洁中文答案。只输出最终答案文本，不输出聊天记录。每个关键结论用自然语言表达，不要输出引用编号、引用占位符或参考资料清单。JSON input:\n{{payload}}";

        public String getFactExtractorSystem() { return factExtractorSystem; }
        public void setFactExtractorSystem(String factExtractorSystem) { this.factExtractorSystem = factExtractorSystem; }
        public String getFactExtractorUserTemplate() { return factExtractorUserTemplate; }
        public void setFactExtractorUserTemplate(String factExtractorUserTemplate) { this.factExtractorUserTemplate = factExtractorUserTemplate; }
        public String getClaimPlanSystem() { return claimPlanSystem; }
        public void setClaimPlanSystem(String claimPlanSystem) { this.claimPlanSystem = claimPlanSystem; }
        public String getClaimPlanUserTemplate() { return claimPlanUserTemplate; }
        public void setClaimPlanUserTemplate(String claimPlanUserTemplate) { this.claimPlanUserTemplate = claimPlanUserTemplate; }
        public String getRenderSystem() { return renderSystem; }
        public void setRenderSystem(String renderSystem) { this.renderSystem = renderSystem; }
        public String getRenderUserTemplate() { return renderUserTemplate; }
        public void setRenderUserTemplate(String renderUserTemplate) { this.renderUserTemplate = renderUserTemplate; }
    }

    public static class RiskPolicy {
        private List<String> strongStatusTerms = new ArrayList<>(Arrays.asList(
                "\u5df2\u4efb\u547d", "\u5df2\u4efb\u804c", "\u5df2\u664b\u5347", "\u73b0\u4efb",
                "\u4efb\u547d", "\u51b3\u5b9a\u4efb\u804c", "\u6b63\u5f0f\u4efb\u804c",
                "\u4efb\u804c\u901a\u77e5", "\u4efb\u804c", "\u664b\u5347", "\u5347\u4efb",
                "\u63d0\u62d4", "\u63d0\u4efb", "\u514d\u804c"));
        private List<String> weakStatusTerms = new ArrayList<>(Arrays.asList(
                "\u62df\u4efb", "\u62df\u63d0\u62d4", "\u5019\u9009", "\u5019\u9009\u4eba", "\u516c\u793a"));
        private List<String> cautiousAnswerTerms = new ArrayList<>(Arrays.asList(
                "\u672a\u627e\u5230", "\u6ca1\u6709\u660e\u786e", "\u4e0d\u80fd\u786e\u8ba4",
                "\u65e0\u6cd5\u786e\u8ba4", "\u4ec5\u663e\u793a", "\u4ec5\u63d0\u5230",
                "\u6750\u6599\u663e\u793a", "\u6750\u6599\u63d0\u5230"));
        private List<String> promotionQueryTerms = new ArrayList<>(Arrays.asList("晋升", "升任", "提拔", "提任"));
        private List<String> promotionEvidenceTerms = new ArrayList<>(Arrays.asList("晋升", "升任", "提拔", "提任", "任命", "决定任职", "正式任职", "任职通知"));
        private String promotionMissingAspect = "promotion_not_explicit";
        private String promotionInsufficientInstruction = "请先说明材料没有明确确认相关状态。如果材料出现拟任或现任等信息，只能表述为材料提到的任职变动信息，不得改写为已发生事实。";

        public List<String> getStrongStatusTerms() { return strongStatusTerms; }
        public void setStrongStatusTerms(List<String> strongStatusTerms) { this.strongStatusTerms = strongStatusTerms == null ? new ArrayList<>() : strongStatusTerms; }
        public List<String> getWeakStatusTerms() { return weakStatusTerms; }
        public void setWeakStatusTerms(List<String> weakStatusTerms) { this.weakStatusTerms = weakStatusTerms == null ? new ArrayList<>() : weakStatusTerms; }
        public List<String> getCautiousAnswerTerms() { return cautiousAnswerTerms; }
        public void setCautiousAnswerTerms(List<String> cautiousAnswerTerms) { this.cautiousAnswerTerms = cautiousAnswerTerms == null ? new ArrayList<>() : cautiousAnswerTerms; }
        public List<String> getPromotionQueryTerms() { return promotionQueryTerms; }
        public void setPromotionQueryTerms(List<String> promotionQueryTerms) { this.promotionQueryTerms = promotionQueryTerms == null ? new ArrayList<>() : promotionQueryTerms; }
        public List<String> getPromotionEvidenceTerms() { return promotionEvidenceTerms; }
        public void setPromotionEvidenceTerms(List<String> promotionEvidenceTerms) { this.promotionEvidenceTerms = promotionEvidenceTerms == null ? new ArrayList<>() : promotionEvidenceTerms; }
        public String getPromotionMissingAspect() { return promotionMissingAspect; }
        public void setPromotionMissingAspect(String promotionMissingAspect) { this.promotionMissingAspect = promotionMissingAspect; }
        public String getPromotionInsufficientInstruction() { return promotionInsufficientInstruction; }
        public void setPromotionInsufficientInstruction(String promotionInsufficientInstruction) { this.promotionInsufficientInstruction = promotionInsufficientInstruction; }
    }
}
