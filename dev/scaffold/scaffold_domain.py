import os

DOMAIN_DIR = "sc-domain/src/main/java/com/swarmcoder/domain"
os.makedirs(DOMAIN_DIR, exist_ok=True)

models = {
    "ArtifactRef.java": "public record ArtifactRef(java.util.UUID id, long revision) {}",
    "WorkflowKind.java": "public enum WorkflowKind { GREENFIELD, ENHANCEMENT, BUGFIX, REFACTOR, DOCS, ANALYSIS }",
    "TaskState.java": "public enum TaskState { PENDING, READY, DISPATCHED, VERIFYING, JUDGING, SELECTED, INTEGRATED, BLOCKED, CANCELLED, DONE }",
    "RunState.java": "public enum RunState { INTAKE, DESIGN, DESIGN_REVIEW, PLAN, TEST_AUTHORING, EXECUTING, FINAL_INTEGRATION, APPROVAL, DELIVERED, ABORTED }",
    
    "Requirement.java": "public record Requirement(java.util.UUID id, String text, Priority priority) {}",
    "Priority.java": "public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }",
    "ApiContract.java": "public record ApiContract(java.util.UUID id, String name, String description, String signatureSketch) {}",
    "ArchDecision.java": "public record ArchDecision(java.util.UUID id, String decision, String rationale, java.util.List<String> alternatives) {}",
    "Risk.java": "public record Risk(java.util.UUID id, String description, Severity severity, String mitigation) {}",
    "Severity.java": "public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }",
    "ReviewVerdict.java": "public enum ReviewVerdict { APPROVED, REJECTED, NEEDS_WORK }",
    
    "DesignDocument.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("DesignDocument")
public record DesignDocument(java.util.UUID id, long revision, String goal,
    java.util.List<Requirement> requirements, java.util.List<ArchDecision> decisions,
    java.util.List<ApiContract> contracts, java.util.List<Risk> risks,
    ReviewVerdict review, java.time.Instant createdAt) {}""",
    
    "TaskGraph.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("TaskGraph")
public record TaskGraph(java.util.UUID id, long revision, java.util.UUID designId,
    java.util.List<Task> tasks, java.util.List<TaskEdge> dependencies) {}""",
    "TaskEdge.java": "public record TaskEdge(java.util.UUID from, java.util.UUID to) {}",
    
    "Task.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("Task")
public record Task(java.util.UUID id, long revision, String title, String instructions,
    java.util.Set<String> writeSet, java.util.Set<String> readSet,
    java.util.List<AcceptanceCriterion> criteria, String acceptanceTestDir,
    java.util.UUID knowledgeBriefId, TokenBudget budget, SwarmPolicy swarmPolicy, TaskState state) {}""",
    
    "AcceptanceCriterion.java": "public record AcceptanceCriterion(java.util.UUID id, String text, String testClassOrFile) {}",
    "TokenBudget.java": "public record TokenBudget(long maxPromptTokens, long maxCompletionTokensPerTurn, long maxTotalTokens, int maxToolTurns) {}",
    "SwarmPolicy.java": "public record SwarmPolicy(int n, boolean splitAcrossFamilies, double tempMin, double tempMax, java.util.List<String> personaIds) {}",
    
    "KnowledgeBrief.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("KnowledgeBrief")
public record KnowledgeBrief(java.util.UUID id, long revision, java.util.UUID taskId,
    java.util.List<LibraryDoc> libraries, java.util.List<InternalApi> internalApis,
    String renderedMarkdown) {}""",
    "LibraryDoc.java": "public record LibraryDoc(String coordinate, String version, String docsExcerpt) {}",
    "InternalApi.java": "public record InternalApi(String file, String signature, String docComment) {}",
    
    "LearnedGuideline.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("LearnedGuideline")
public record LearnedGuideline(java.util.UUID id, long revision, GuidelineScope scope,
    String slug, String markdownBody, Provenance provenance, double confidence,
    java.time.Instant lastUsed, int useCount, GuidelineStatus status) {}""",
    "GuidelineScope.java": "public enum GuidelineScope { GLOBAL, PROJECT, TASK_FAMILY }",
    "Provenance.java": "public record Provenance(String source, java.util.UUID sourceRunId) {}",
    "GuidelineStatus.java": "public enum GuidelineStatus { ACTIVE, PROPOSED, RETIRED }",
    
    "SamplingConfig.java": "public record SamplingConfig(String modelProfileId, double temperature, long seed, String personaId, String contextSliceId) {}",
    
    "CandidateSolution.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("CandidateSolution")
public record CandidateSolution(java.util.UUID id, java.util.UUID taskId, int workerIndex, String branch,
    SamplingConfig sampling, String diffUnified, VerificationReport verification, ClusterId cluster, JudgeScore judge,
    CandidateState state, KillReason killReason) {}""",
    "CandidateState.java": "public enum CandidateState { RUNNING, KILLED, FAILED, SURVIVED, SELECTED, ARCHIVED }",
    "KillReason.java": "public enum KillReason { PARSE_FAIL, TOOLCALL_MALFORMED, BUDGET_EXCEEDED, WRITESET_VIOLATION, COMPILE_FAIL_TWICE, TIMEOUT, SUPERSEDED }",
    
    "VerificationReport.java": """public record VerificationReport(java.util.UUID id, boolean parses, boolean compiles,
    TestResults acceptance, TestResults existing, LintResults lint,
    BrowserCheckResults browser, java.time.Duration wallTime, String logTail, String fullLogRef) {}""",
    "TestResults.java": "public record TestResults(int passed, int failed, int errored, int skipped, java.util.List<TestFailure> failures) {}",
    "TestFailure.java": "public record TestFailure(String testId, String message, String truncatedTrace) {}",
    "LintResults.java": "public record LintResults(int errors, int warnings, java.util.List<String> messages) {}",
    "BrowserCheckResults.java": "public record BrowserCheckResults(java.util.List<PageCheck> checks) {}",
    "PageCheck.java": "public record PageCheck(String url, boolean loaded, java.util.List<String> consoleErrors, java.util.List<AssertionResult> assertions, String screenshotRef) {}",
    "AssertionResult.java": "public record AssertionResult(String selector, boolean passed, String message) {}",
    
    "ClusterId.java": "public record ClusterId(String behavioralHash, int clusterSize) {}",
    "JudgeScore.java": "public record JudgeScore(double score, String rationale, String judgeModelId) {}",
    
    "ContextCheckpoint.java": "public record ContextCheckpoint(java.util.UUID id, java.util.UUID ownerAgentSessionId, int messageIndex, String prefixHash, java.time.Instant at, String label) {}",
    "FixSummary.java": "public record FixSummary(java.util.UUID id, java.util.UUID taskId, String rootCause, String changeMade, String guidelineCandidate, java.util.UUID debugSessionId) {}",
    
    "Run.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("Run")
public record Run(java.util.UUID id, WorkflowKind kind, RunState state, java.util.UUID designId, java.util.UUID taskGraphId,
    Budget cloudBudget, java.time.Instant startedAt, RunReport report) {}""",
    "Budget.java": "public record Budget(long maxCloudTokens, long usedCloudTokens, java.time.Duration wallClockCeiling, long maxLocalTokensPerTask) {}",
    "Decision.java": """import com.fasterxml.jackson.annotation.JsonTypeName;
@JsonTypeName("Decision")
public record Decision(java.util.UUID id, java.util.UUID runId, DecisionKind kind, String briefMarkdown,
    DecisionState state, String humanResponse, java.time.Instant createdAt) {}""",
    "DecisionKind.java": "public enum DecisionKind { APPROVAL, BLOCKED_TASK, GUIDELINE_REVIEW, BUDGET_EXTENSION }",
    "DecisionState.java": "public enum DecisionState { PENDING, RESOLVED }",
    "RunReport.java": "public record RunReport(java.util.UUID runId, String summary) {}"
}

for filename, content in models.items():
    filepath = os.path.join(DOMAIN_DIR, filename)
    with open(filepath, "w") as f:
        f.write("package com.swarmcoder.domain;\n\n")
        f.write(content + "\n")

print(f"Generated {len(models)} domain records.")
