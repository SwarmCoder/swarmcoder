import os

SWARM_DIR = "sc-swarm/src/main/java/com/swarmcoder/swarm"
os.makedirs(SWARM_DIR, exist_ok=True)

models = {
    os.path.join(SWARM_DIR, "SwarmDispatcher.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.*;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.git.GitService;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.runtime.AgentRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SwarmDispatcher {
    private final InferenceScheduler scheduler;
    private final GitService gitService;
    private final DockerSandboxManager sandboxManager;
    private final AgentRuntime runtime;

    public SwarmDispatcher(InferenceScheduler scheduler, GitService gitService,
                           DockerSandboxManager sandboxManager, AgentRuntime runtime) {
        this.scheduler = scheduler;
        this.gitService = gitService;
        this.sandboxManager = sandboxManager;
        this.runtime = runtime;
    }

    public List<CandidateSolution> dispatch(Task task) {
        SwarmPolicy policy = task.swarmPolicy();
        int n = policy.n();
        List<SamplingConfig> configs = new ArrayList<>();
        
        // Diversity matrix generation
        String familyA = "qwen3-coder-next";
        String familyB = "qwen35-35b";
        
        for (int i = 0; i < n; i++) {
            String profileId = policy.splitAcrossFamilies() && i % 2 != 0 ? familyB : familyA;
            double temp = policy.tempMin() + (policy.tempMax() - policy.tempMin()) * ((double)i / Math.max(1, n - 1));
            String persona = policy.personaIds() != null && !policy.personaIds().isEmpty() ? 
                             policy.personaIds().get(i % policy.personaIds().size()) : "minimal-diff";
            configs.add(new SamplingConfig(profileId, temp, i * 100L, persona, "full-files"));
        }

        List<CandidateSolution> candidates = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final SamplingConfig cfg = configs.get(i);
                executor.submit(() -> {
                    WorkerLoop loop = new WorkerLoop(task, cfg, idx, scheduler, gitService, sandboxManager, runtime);
                    CandidateSolution sol = loop.run();
                    synchronized (candidates) {
                        candidates.add(sol);
                    }
                });
            }
        }
        
        return candidates;
    }
}
""",

    os.path.join(SWARM_DIR, "WorkerLoop.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.*;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.git.GitService;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.runtime.AgentRuntime;

import java.util.UUID;

public class WorkerLoop {
    private final Task task;
    private final SamplingConfig config;
    private final int workerIndex;
    private final InferenceScheduler scheduler;
    private final GitService gitService;
    private final DockerSandboxManager sandboxManager;
    private final AgentRuntime runtime;

    public WorkerLoop(Task task, SamplingConfig config, int workerIndex,
                      InferenceScheduler scheduler, GitService gitService,
                      DockerSandboxManager sandboxManager, AgentRuntime runtime) {
        this.task = task;
        this.config = config;
        this.workerIndex = workerIndex;
        this.scheduler = scheduler;
        this.gitService = gitService;
        this.sandboxManager = sandboxManager;
        this.runtime = runtime;
    }

    public CandidateSolution run() {
        String branch = "swarm/" + task.id() + "/" + workerIndex;
        
        // M2 Stub for virtual thread loop
        System.out.println("Worker " + workerIndex + " started with profile " + config.modelProfileId());
        
        try {
            gitService.checkoutBranch(branch, true);
            // Simulate agent work and acquisition of lease
            InferenceScheduler.Lease lease = scheduler.acquireLease(config.modelProfileId(), 65536);
            
            // Loop until done or killed (EarlyKillEnforcer would run here)
            Thread.sleep(100);
            
            scheduler.releaseLease(lease);
        } catch (Exception e) {
            e.printStackTrace();
            return new CandidateSolution(UUID.randomUUID(), task.id(), workerIndex, branch, config, "", null, null, null, CandidateState.FAILED, KillReason.TIMEOUT);
        }

        return new CandidateSolution(UUID.randomUUID(), task.id(), workerIndex, branch, config, "dummy diff", null, null, null, CandidateState.SURVIVED, null);
    }
}
""",

    os.path.join(SWARM_DIR, "EarlyKillEnforcer.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.KillReason;

public class EarlyKillEnforcer {
    public KillReason checkState(int malformedCount, int writeSetViolations, long usedTokens, long maxTokens) {
        if (malformedCount >= 2) return KillReason.TOOLCALL_MALFORMED;
        if (writeSetViolations >= 2) return KillReason.WRITESET_VIOLATION;
        if (usedTokens > maxTokens) return KillReason.BUDGET_EXCEEDED;
        return null;
    }
    
    public KillReason checkParallelCompile(boolean thisFailed, int totalOtherCandidatesCompiled) {
        if (thisFailed && totalOtherCandidatesCompiled >= 2) {
            return KillReason.COMPILE_FAIL_TWICE; // Using twice logic to kill if others succeeded
        }
        return null;
    }
}
""",

    os.path.join(SWARM_DIR, "SyntacticClusterer.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.syntax.SyntaxService;
import com.swarmcoder.syntax.Language;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyntacticClusterer {
    private final SyntaxService syntaxService;

    public SyntacticClusterer(SyntaxService syntaxService) {
        this.syntaxService = syntaxService;
    }

    public List<CandidateSolution> cluster(List<CandidateSolution> survived, Language lang) {
        Map<String, List<CandidateSolution>> clusters = new HashMap<>();
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CandidateSolution sol : survived) {
                String normalized = syntaxService.normalizeForClustering(lang, sol.diffUnified());
                byte[] hashBytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hashBytes) {
                    hex.append(String.format("%02x", b));
                }
                String hash = hex.toString();
                clusters.computeIfAbsent(hash, k -> new ArrayList<>()).add(sol);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<CandidateSolution> clustered = new ArrayList<>();
        for (Map.Entry<String, List<CandidateSolution>> entry : clusters.entrySet()) {
            ClusterId clusterId = new ClusterId(entry.getKey(), entry.getValue().size());
            for (CandidateSolution sol : entry.getValue()) {
                CandidateSolution updated = new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(),
                    sol.branch(), sol.sampling(), sol.diffUnified(), sol.verification(), clusterId,
                    sol.judge(), sol.state(), sol.killReason());
                clustered.add(updated);
            }
        }
        return clustered;
    }
}
""",

    os.path.join(SWARM_DIR, "JudgeClient.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.inference.VllmClient;

public class JudgeClient {
    private final VllmClient client;

    public JudgeClient(VllmClient client) {
        this.client = client;
    }

    public CandidateSolution judge(CandidateSolution sol, String judgeModelId) {
        // Stubbed judging logic per spec using deepseek v4 pro
        System.out.println("Judging candidate " + sol.id() + " using " + judgeModelId);
        JudgeScore score = new JudgeScore(0.9, "Looks solid", judgeModelId);
        
        return new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(),
            sol.branch(), sol.sampling(), sol.diffUnified(), sol.verification(), sol.cluster(),
            score, sol.state(), sol.killReason());
    }
}
""",

    os.path.join(SWARM_DIR, "SelectionLogic.java"): """package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;

import java.util.List;

public class SelectionLogic {
    public CandidateSolution selectWinner(List<CandidateSolution> judged) {
        if (judged == null || judged.isEmpty()) return null;

        CandidateSolution best = judged.get(0);
        for (CandidateSolution current : judged) {
            if (current.judge() == null) continue;
            if (best.judge() == null || current.judge().score() > best.judge().score()) {
                best = current;
            } else if (current.judge().score() == best.judge().score()) {
                if (current.cluster().clusterSize() > best.cluster().clusterSize()) {
                    best = current;
                }
            }
        }
        
        return new CandidateSolution(best.id(), best.taskId(), best.workerIndex(),
            best.branch(), best.sampling(), best.diffUnified(), best.verification(), best.cluster(),
            best.judge(), CandidateState.SELECTED, best.killReason());
    }
}
"""
}

for filepath, content in models.items():
    with open(filepath, "w") as f:
        f.write(content)

print("Scaffolded sc-swarm.")
