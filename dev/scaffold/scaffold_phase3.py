import os

RUNTIME_DIR = "sc-runtime/src/main/java/com/swarmcoder/runtime"
GIT_DIR = "sc-git/src/main/java/com/swarmcoder/git"
TUI_DIR = "sc-tui/src/main/java/com/swarmcoder/tui"
APP_DIR = "sc-app/src/main/java/com/swarmcoder/app"

os.makedirs(RUNTIME_DIR, exist_ok=True)
os.makedirs(GIT_DIR, exist_ok=True)
os.makedirs(TUI_DIR, exist_ok=True)
os.makedirs(APP_DIR, exist_ok=True)

models = {
    os.path.join(RUNTIME_DIR, "KoogFacade.java"): """package com.swarmcoder.runtime;

import java.nio.file.Path;

public interface KoogFacade {
    /**
     * Parses the workspace and returns an AST string representation.
     * This isolates the Koog AST types from the rest of the domain.
     */
    String parseWorkspace(Path workspaceRoot);
    
    /**
     * Indexes the symbols in the workspace.
     */
    void indexWorkspace(Path workspaceRoot);
}""",

    os.path.join(RUNTIME_DIR, "SwarmEngine.java"): """package com.swarmcoder.runtime;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;

public class SwarmEngine {
    private final VllmClient vllmClient;
    private final ArtifactStore artifactStore;
    private final DockerSandboxManager sandboxManager;
    private final InferenceScheduler inferenceScheduler;
    private final KoogFacade koogFacade;

    public SwarmEngine(VllmClient vllmClient, ArtifactStore artifactStore, 
                       DockerSandboxManager sandboxManager, InferenceScheduler inferenceScheduler,
                       KoogFacade koogFacade) {
        this.vllmClient = vllmClient;
        this.artifactStore = artifactStore;
        this.sandboxManager = sandboxManager;
        this.inferenceScheduler = inferenceScheduler;
        this.koogFacade = koogFacade;
    }

    public void start() {
        System.out.println("SwarmEngine started. Awaiting runs...");
    }
    
    public void executeRun(Run run) {
        // M1 Single Worker Pipeline logic goes here
        System.out.println("Executing run: " + run.id());
    }
}""",

    os.path.join(GIT_DIR, "GitService.java"): """package com.swarmcoder.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class GitService {
    private final Path workspaceDir;

    public GitService(Path workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public void checkoutBranch(String branchName, boolean create) throws IOException, GitAPIException {
        try (Git git = Git.open(workspaceDir.toFile())) {
            git.checkout().setCreateBranch(create).setName(branchName).call();
        }
    }

    public void commitChanges(String message) throws IOException, GitAPIException {
        try (Git git = Git.open(workspaceDir.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
        }
    }
}""",

    os.path.join(TUI_DIR, "TerminalIntake.java"): """package com.swarmcoder.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import java.util.function.Consumer;

public class TerminalIntake {

    public static void showIntake(Consumer<String> onTaskSubmitted) {
        try {
            Terminal terminal = new DefaultTerminalFactory().createTerminal();
            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();

            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(), new EmptySpace());

            BasicWindow window = new BasicWindow("SwarmCoder Intake");
            Panel panel = new Panel();
            panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));

            panel.addComponent(new Label("Enter your task/feature request:"));
            TextBox taskBox = new TextBox(new TerminalSize(50, 10));
            panel.addComponent(taskBox);

            panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));
            
            Button submitBtn = new Button("Submit", () -> {
                String task = taskBox.getText();
                if (!task.trim().isEmpty()) {
                    window.close();
                    try {
                        screen.stopScreen();
                    } catch (Exception e) {}
                    onTaskSubmitted.accept(task);
                }
            });
            panel.addComponent(submitBtn);

            window.setComponent(panel);
            gui.addWindowAndWait(window);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}""",

    os.path.join(APP_DIR, "DependencyGraph.java"): """package com.swarmcoder.app;

import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.git.GitService;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.KoogFacade;
import com.swarmcoder.runtime.SwarmEngine;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

public class DependencyGraph {
    public final SwarmConfig config;
    public final ArtifactStore artifactStore;
    public final VllmClient vllmClient;
    public final InferenceScheduler inferenceScheduler;
    public final DockerSandboxManager sandboxManager;
    public final KoogFacade koogFacade;
    public final SwarmEngine swarmEngine;
    public final GitService gitService;

    public DependencyGraph() throws IOException {
        // 1. Config
        this.config = ConfigLoader.loadDefaultConfig();

        // 2. Store
        Path storePath = Paths.get(System.getProperty("user.home"), ".swarmcoder", "store");
        this.artifactStore = new ArtifactStore(storePath);

        // 3. Inference
        boolean useFormat = true;
        this.vllmClient = new VllmClient("http://192.168.0.10:8000", "vllm-model", useFormat);
        this.inferenceScheduler = new InferenceScheduler(10, 1024 * 1024 * 1024, 1024);

        // 4. Sandbox
        this.sandboxManager = new DockerSandboxManager();

        // 5. Koog
        this.koogFacade = new KoogFacade() {
            @Override
            public String parseWorkspace(Path workspaceRoot) { return "ast"; }
            @Override
            public void indexWorkspace(Path workspaceRoot) {}
        };

        // 6. Git
        this.gitService = new GitService(Paths.get(System.getProperty("user.dir")));

        // 7. Engine
        this.swarmEngine = new SwarmEngine(vllmClient, artifactStore, sandboxManager, inferenceScheduler, koogFacade);
    }

    public void close() throws Exception {
        artifactStore.close();
    }
}""",

    os.path.join(APP_DIR, "Main.java"): """package com.swarmcoder.app;

import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.WorkflowKind;
import com.swarmcoder.tui.TerminalIntake;

import java.time.Instant;
import java.util.UUID;

public class Main {
    public static void main(String[] args) {
        System.out.println("Starting SwarmCoder...");
        
        try {
            DependencyGraph graph = new DependencyGraph();
            graph.swarmEngine.start();
            
            TerminalIntake.showIntake(taskDescription -> {
                System.out.println("Received task: " + taskDescription);
                Run run = new Run(UUID.randomUUID(), WorkflowKind.GREENFIELD, RunState.INTAKE, null, null, null, Instant.now(), null);
                
                try {
                    graph.artifactStore.append(run, () -> {
                        graph.artifactStore.root().runs.put(run.id(), run);
                        return null;
                    }).get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                
                graph.swarmEngine.executeRun(run);
            });
            
            // Keep alive
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}"""
}

for filepath, content in models.items():
    with open(filepath, "w") as f:
        f.write(content)

print(f"Generated {len(models)} java files.")
