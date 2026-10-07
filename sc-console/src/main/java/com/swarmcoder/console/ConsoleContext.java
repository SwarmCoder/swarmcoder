/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.console;

import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.console.api.RoleEntryDto;
import com.swarmcoder.domain.Project;
import com.swarmcoder.store.BlobStore;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Bridge between the orchestrator's manually wired singletons (DependencyGraph) and the
 * Console's CDI beans, which TomEE instantiates in its own container. Set once before
 * {@code ConsoleServer.start(...)}.
 */
public final class ConsoleContext {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ConsoleContext.class);

    private static volatile ConsoleContext instance;

    private final ArtifactStore store;
    private final TraceHub traceHub;
    private final BlobStore blobStore; // nullable
    private final BiFunction<String, String, UUID> intake; // (goal, workflowKind) -> runId
    private final Consumer<UUID> approve;
    private final Consumer<UUID> reject;
    private Supplier<String> settingsReader = () -> "";
    private Function<String, String> settingsWriter = yaml -> "settings not writable";
    /**
     * The on-ramp for a codebase nobody has written a verification contract for yet. Wired by
     * sc-app because the detection lives in sc-verify, which the console does not depend on.
     * Unwired it says so rather than pretending the folder cannot be built.
     */
    private BuildContracts buildContracts = new BuildContracts() {};
    /** (query, max) -> matching session ids, newest/most-relevant first. */
    private BiFunction<String, Integer, List<String>> historySearch =
        (q, max) -> List.of();
    // Multi-project registry bridge (defaults: single implicit project, not creatable/switchable).
    private Supplier<List<Project>> projectLister =
        List::of;
    private Supplier<UUID> currentProjectIdSupplier = () -> null;
    private ProjectCreator projectCreator = (name, path, ctx) -> {
        throw new IllegalStateException("projects are not creatable in this context");
    };
    private Consumer<UUID> projectSwitcher = id -> { };

    /** Creates or re-uses a project. */
    @FunctionalInterface
    public interface ProjectCreator {
        Project create(String name, String primaryPath, List<String> contextPaths);
    }

    /**
     * The chat coder's LLM (CONSOLE_DESIGN_V2.md §5.2): OpenAI-style messages in, streamed
     * text chunks out. Implemented in sc-app over the current project's chat client so
     * sc-console never depends on sc-inference.
     */
    @FunctionalInterface
    public interface ChatModel {
        /**
         * @param modelOverride the chat's per-chat model name, or null/blank for the project
         *                      default — sc-app resolves it to the right endpoint client.
         */
        Stream<String> stream(List<Map<String, String>> messages,
                                               String modelOverride)
            throws Exception;
    }

    /**
     * The analyst's research tools (author requirement 2026-07-14): confined read-only
     * access to the project + context folders, implemented in sc-app over the Librarian's
     * curator. Addresses are {@code <rootLabel>/<relative-path>}.
     */
    public interface ChatTools {
        String listFolder(String address);
        String readFile(String address);
        String searchCode(String query);
        /** Versioned library docs (local index → curator sources → Context7). */
        String lookupDocs(String query);
        /** File addresses matching a mention fragment, for @-mention autocomplete. */
        List<String> mentionCandidates(String query);
    }

    /**
     * The Researcher agent bridge (author requirement 2026-07-14): implemented in sc-app
     * over the current project's {@code ResearcherAgent}. {@code start} returns "" or an
     * error; {@code status} is a live one-line summary.
     */
    public interface Researcher {
        String start(String topic);
        String status();
    }

    /**
     * Live health probe (design §8), implemented in sc-app where the inference endpoints,
     * sandbox, and CloudGate live. Defaults report "unknown"/0 so the strip degrades gracefully
     * in spike/test contexts. {@code sparkStatus}/{@code dockerStatus} are short status words
     * ("up"/"down"/"unknown", "on"/"off"/"unknown").
     */
    public interface Health {
        String sparkStatus();
        String dockerStatus();
        long budgetUsed();
        long budgetMax();

        /**
         * Detail behind {@link #sparkStatus()}: the endpoint probed, the models it reports, and why
         * it failed when it did.
         *
         * <p>Defaulted rather than abstract because a status word alone is a complete answer for the
         * spike and test contexts that implement this seam — and because "down" without the URL it
         * was measured against is unactionable, which is the gap these close.
         */
        default String sparkUrl() { return null; }
        default String sparkModels() { return null; }
        default String sparkDetail() { return null; }
    }

    private Health health = new Health() {
        public String sparkStatus() { return "unknown"; }
        public String dockerStatus() { return "unknown"; }
        public long budgetUsed() { return 0; }
        public long budgetMax() { return 0; }
    };

    public ConsoleContext withHealth(Health health) {
        if (health != null) {
            this.health = health;
        }
        return this;
    }

    public Health health() {
        return health;
    }

    /**
     * Vision-capable model used by the BRD document intake to read an uploaded image
     * (docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md §4.3). Wired in sc-app from {@code roles.vision}.
     *
     * <p>There is deliberately NO fallback to a text role: substituting one would produce a
     * confident description of an image the model never saw. Left unwired, image upload is refused
     * with a message naming the missing configuration.
     */
    public interface VisionModel {
        /** @return the model's reading of the image; throws if the endpoint is unreachable. */
        String describe(String prompt, String imageDataUri) throws Exception;
        /** Model id, recorded for provenance so an operator can see what did the reading. */
        String modelName();
    }

    private VisionModel vision;

    public ConsoleContext withVision(VisionModel vision) {
        this.vision = vision;
        return this;
    }

    /** Null when {@code roles.vision} is unconfigured — callers must refuse images, not guess. */
    public VisionModel vision() {
        return vision;
    }

    /**
     * The project's rules: turning one on or off, giving one a check, and recording one the
     * operator has stated.
     *
     * <p>A seam rather than a direct call because the rules belong to the CURRENT project and only
     * the wiring knows which that is; the Console module may not depend on the knowledge module
     * that owns them. Left unwired (tests, spikes), the screen says the rule cannot be changed here
     * rather than silently doing nothing.
     */
    public interface GuidelineControl {
        /** @return "" on success, else "error: …" — the wording shown to the operator */
        String setStatus(UUID guidelineId, String status);

        /**
         * Gives a rule the one-line command that proves it was obeyed, or removes it (blank).
         * A candidate whose workspace fails the command does not survive verification.
         *
         * @return "" on success, else "error: …"
         */
        String setCheck(UUID guidelineId, String command, int timeoutSeconds);

        /**
         * Records a rule a person stated — how a technical document becomes rules.
         *
         * <p>It arrives IN USE, not proposed. By the time this is called the operator has written
         * the rule, attached the document, ticked that it says how the system must be built, read
         * the analyst's reading of it back, and ticked it to apply. A further switch on another
         * screen between all of that and any effect is a dead end, not a safeguard.
         *
         * <p>Stated again in the same words, it is the same rule: the store is matched on wording
         * and the existing rule is brought back into force rather than duplicated.
         *
         * @param title    a short name for the rule; it becomes the rule's heading
         * @param body     the rule in full, in the document's own words
         * @param document the file it was read out of, kept as the rule's provenance
         * @return "" on success, else "error: …"
         */
        String stateRule(String title, String body, String document);

        /**
         * As above, and also keeps the document's OWN sentence(s) the rule was drawn from,
         * verbatim — not {@code body}'s restatement of them, which is what the analyst wrote at
         * intake and can drop the one word (a backticked artifact id, most often) the document
         * actually used.
         *
         * <p>Default delegates to the three-argument form with no excerpt, so an implementation
         * written before this existed keeps compiling and simply carries no excerpt forward.
         *
         * @param excerpt the document's own wording; null when there is none to quote
         * @return "" on success, else "error: …"
         */
        default String stateRule(String title, String body, String document, String excerpt) {
            return stateRule(title, body, document);
        }

        /**
         * As above, and also says why the rule exists and whether it is HARD (harness runs 53 and
         * 55, 2026-10-01): the judge holds work to a rule's purpose, and only a hard rule's break
         * can stop a task.
         *
         * <p>Default delegates to the four-argument form, so an implementation written before this
         * existed keeps compiling and records every rule as an unexplained preference.
         *
         * @param purpose one line — the problem the rule prevents; null when none was given
         * @param hard    true for a MUST, a prohibition or a fixed part of the stack
         */
        default String stateRule(String title, String body, String document, String excerpt,
                                 String purpose, boolean hard) {
            return stateRule(title, body, document, excerpt);
        }

        /**
         * As above, and also says which part of the project the rule applies to (owner's
         * decision 2026-10-07): folders from the repository root, a module being its folder. A
         * worker is then sent the rule only when its task may write there. Each entry is checked
         * against the project's tree by whoever owns the rules; one unknown entry records the
         * rule for the whole project.
         *
         * <p>Default delegates to the six-argument form, so an implementation written before
         * this existed keeps compiling and records every rule for the whole project.
         *
         * @param appliesTo the folders the rule is about; null or empty is the whole project
         */
        default String stateRule(String title, String body, String document, String excerpt,
                                 String purpose, boolean hard, java.util.List<String> appliesTo) {
            return stateRule(title, body, document, excerpt, purpose, hard);
        }

        /**
         * The module folders of the current project, from its tree: what the analyst may name
         * as the part a rule applies to. Empty when the project has no tree yet, and then every
         * rule is a rule of the whole project.
         */
        default java.util.List<String> ruleScopes() {
            return java.util.List.of();
        }

        /**
         * Retires the rules a document stated EARLIER, because it is about to state them again.
         *
         * <p>Called once, immediately before a batch of {@link #stateRule} calls that all cite the
         * same document. A document is one artifact: the rules in force from it are what it says
         * NOW, not the union of every version of it ever read. Without this, re-reading one
         * technical document produced a complete second set of rules every time — the analyst
         * words them slightly differently, so nothing ever saw a duplicate. On the operator's live
         * project one document had been read four times and left 44 rules in force where it states
         * about eleven things, and every copy of every rule went into every worker's prompt, the
         * architect's, the test author's and the judge's.
         *
         * <p>Nothing is deleted. The rules become RETIRED, still appear on the Guidelines screen,
         * and can be switched back on in one click. A rule the document still states in the same
         * words comes straight back under its old id when it is stated again.
         *
         * @return how many rules from that document's previous statement were retired
         */
        int supersedeRulesFrom(String document);
    }

    private GuidelineControl guidelineControl;

    public ConsoleContext withGuidelineControl(GuidelineControl control) {
        this.guidelineControl = control;
        return this;
    }

    /** Null when nothing wired a project's rules in — the screen must say so. */
    public GuidelineControl guidelineControl() {
        return guidelineControl;
    }

    private Researcher researcher;

    public ConsoleContext withResearcher(Researcher researcher) {
        this.researcher = researcher;
        return this;
    }

    public Researcher researcher() {
        return researcher;
    }

    private ChatTools chatTools;

    public ConsoleContext withChatTools(ChatTools tools) {
        this.chatTools = tools;
        return this;
    }

    /** Null when no research tools are wired (spike/test contexts). */
    public ChatTools chatTools() {
        return chatTools;
    }

    private ChatModel chatModel;
    /** (topic, payload) → browser push; connected by ConsoleServer once the engine is up. */
    private volatile BiConsumer<String, Object> pushSink = (topic, payload) -> { };

    /**
     * Typed settings forms (design §7), implemented in sc-app where the config classes
     * live: global roles/budgets map onto config.yaml, project entries onto each project's
     * .swarmcoder/project.yaml (with engine-context invalidation on save).
     */
    public interface ConfigForms {
        List<RoleEntryDto> globalRoles();
        String saveGlobalRoles(List<RoleEntryDto> roles);
        BudgetsDto budgets();
        String saveBudgets(BudgetsDto budgets);
        List<RoleEntryDto> projectRoles(String projectId);
        /** The project's own worker count, or 0 when it inherits the global one. */
        int projectWorkersPerTask(String projectId);
        String saveProjectConfig(String projectId, String contextPathsCsv,
                                 List<RoleEntryDto> roles, int workersPerTask);
    }

    private ConfigForms configForms;

    public ConsoleContext(ArtifactStore store, TraceHub traceHub,
                          BiFunction<String, String, UUID> intake,
                          Consumer<UUID> approve, Consumer<UUID> reject) {
        this(store, traceHub, null, intake, approve, reject);
    }

    public ConsoleContext(ArtifactStore store, TraceHub traceHub,
                          BlobStore blobStore,
                          BiFunction<String, String, UUID> intake,
                          Consumer<UUID> approve, Consumer<UUID> reject) {
        this.store = store;
        this.traceHub = traceHub;
        this.blobStore = blobStore;
        this.intake = intake;
        this.approve = approve;
        this.reject = reject;
    }

    /** Wires config read/write (owned by sc-app's ConfigLoader) into the Console. */
    public ConsoleContext withSettings(Supplier<String> reader,
                                        Function<String, String> writer) {
        this.settingsReader = reader;
        this.settingsWriter = writer;
        return this;
    }

    /** Wires the Context Ledger's session history search into the Console. */
    public ConsoleContext withHistorySearch(BiFunction<String, Integer, List<String>> search) {
        this.historySearch = search;
        return this;
    }

    /** Wires the multi-project registry (list/current/create/switch) into the Console. */
    public ConsoleContext withProjects(
            Supplier<List<Project>> lister,
            Supplier<UUID> currentId,
            ProjectCreator creator, Consumer<UUID> switcher) {
        this.projectLister = lister;
        this.currentProjectIdSupplier = currentId;
        this.projectCreator = creator;
        this.projectSwitcher = switcher;
        return this;
    }

    public List<Project> listProjects() {
        return projectLister.get();
    }

    /**
     * How the running process ends what a just-deleted project left going: its cached engine (no
     * new work is dispatched through it) and its worktrees/branches (swept immediately, the same
     * cleanup a run leaves for itself when it finishes normally). A no-op by default — spike and
     * test contexts have no engine or repository to clean up after.
     */
    private Consumer<Project> projectTeardown = project -> { };

    public ConsoleContext withProjectTeardown(Consumer<Project> teardown) {
        this.projectTeardown = teardown == null ? project -> { } : teardown;
        return this;
    }

    /**
     * Called by {@code ControlServiceImpl.deleteProject}, before the project's store records are
     * removed, so the teardown still has {@code project.primaryPath()} in hand.
     */
    void teardownProject(Project project) {
        try {
            projectTeardown.accept(project);
        } catch (Exception e) {
            log.warn("Could not tear down project {} ({}) before deleting it: {}",
                project == null ? "?" : project.name(), project == null ? "?" : project.id(),
                e.toString());
        }
    }

    // --- which project the operator chose, and whether they have chosen one at all --------------

    /**
     * Whether the operator has said which project they are working in, this run of the console.
     *
     * <p><b>Defaults to true</b>, like every other seam on this class: a bare {@code ConsoleContext}
     * is a spike or a test harness with one project wired straight in, and there is nobody there to
     * ask. The real application calls {@link #awaitingProjectChoice} while it wires itself, which is
     * what makes the picker the first thing an operator sees.
     */
    private volatile boolean projectChosen = true;

    /** The project the picker offers first while nothing is chosen; null for no suggestion. */
    private volatile UUID suggestedProjectId;

    /**
     * Starts with nothing chosen, offering {@code suggested} first.
     *
     * <p>Called by the wiring at start-up. The suggestion is where the operator was last time: it is
     * pre-selected so the ordinary day is one keypress, and it still has to be pressed, because
     * landing somebody in a project they did not pick is the whole defect this exists to remove.
     */
    public ConsoleContext awaitingProjectChoice(UUID suggested) {
        this.projectChosen = false;
        this.suggestedProjectId = suggested;
        return this;
    }

    /**
     * Puts the question back, with no suggestion — what a deletion does.
     *
     * <p>Whatever project the machinery has fallen back on, the operator has not chosen it. Before
     * this, deleting a project opened the next one in the list automatically: a screen full of
     * somebody else's work that looked exactly like the project that had just been destroyed, so
     * the obvious response to "that did not work" was to delete it too.
     */
    void requireProjectChoice() {
        this.projectChosen = false;
        this.suggestedProjectId = null;
    }

    public boolean projectChosen() {
        return projectChosen;
    }

    /** The pre-selected project id as the wire carries it, or "" for none. */
    String suggestedProjectId() {
        UUID suggested = suggestedProjectId;
        return suggested == null ? "" : suggested.toString();
    }

    /**
     * Why a run could not start right now for environmental reasons, or null when it could.
     *
     * <p>Wired in sc-app, which is the only place that knows whether the sandbox is required and
     * whether Docker answered. Defaults to "no objection" so spike and test contexts are unaffected.
     */
    private Supplier<String> sandboxBlocker = () -> null;

    public ConsoleContext withSandboxBlocker(Supplier<String> blocker) {
        if (blocker != null) {
            this.sandboxBlocker = blocker;
        }
        return this;
    }

    public String sandboxBlocker() {
        try {
            return sandboxBlocker.get();
        } catch (Exception e) {
            return null;
        }
    }

    public UUID currentProjectId() {
        return currentProjectIdSupplier.get();
    }

    public Project createProject(String name, String primaryPath,
                                                       List<String> contextPaths) {
        refuseFromTheBrowserIfWatching("create a project");
        Project created = projectCreator.create(name, primaryPath, contextPaths);
        // Switch to it. Creating a project is not a filing exercise — it is the operator saying
        // "this is what I am working on now", and leaving them in the old one means every next
        // action lands in the wrong place. Making one IS choosing one, so the picker steps aside.
        projectSwitcher.accept(created.id());
        this.projectChosen = true;
        ProjectPublisher.publish();
        ReadinessPublisher.publish(get());
        return created;
    }

    public void switchProject(UUID projectId) {
        projectSwitcher.accept(projectId);
        // The operator picked this one — from the rail or from the picker. Either way the question
        // "which project" is answered until something unanswers it.
        this.projectChosen = true;
        ProjectPublisher.publish();
        ReadinessPublisher.publish(get());
    }

    /**
     * Moves the machinery onto a project without claiming the operator picked it.
     *
     * <p>Used by a deletion, which has to move off the record it just destroyed — every service
     * resolves through the current project — while leaving the question of which project the
     * operator wants unanswered, so the picker asks it.
     */
    void moveCurrentProjectQuietly(UUID projectId) {
        projectSwitcher.accept(projectId);
        // The shell's counts and its "what next" line are scoped to a project, so leaving them
        // unpublished would keep the DELETED project's numbers on the header behind the picker —
        // which is the very thing the operator said made a deletion not look like one.
        ReadinessPublisher.publish(get());
    }

    /** Project grounding prepended to the chat system prompt (name, folders, models…). */
    private Supplier<String> chatContext = () -> "";

    /** Wires the chat coder's LLM into the Console. */
    public ConsoleContext withChat(ChatModel model) {
        this.chatModel = model;
        return this;
    }

    /** Wires the chat coder's LLM plus a project-context supplier for its system prompt. */
    public ConsoleContext withChat(ChatModel model, Supplier<String> context) {
        this.chatModel = model;
        this.chatContext = context == null ? () -> "" : context;
        return this;
    }

    public String chatContext() {
        try {
            String context = chatContext.get();
            return context == null ? "" : context;
        } catch (Exception e) {
            return "";
        }
    }

    /** Wires the typed settings forms into the Console. */
    public ConsoleContext withConfigForms(ConfigForms forms) {
        this.configForms = forms;
        return this;
    }

    /** Null when no typed forms are wired (spike/test contexts). */
    public ConfigForms configForms() {
        return configForms;
    }

    /** Null when no chat model is wired (spike/test contexts without an LLM). */
    public ChatModel chatModel() {
        return chatModel;
    }

    private ChatModel analystModel;
    private ChatModel plannerModel;

    /** Wires the BRD author behind the "Analyse documents" wizard ({@code roles.requirementsAnalyst}). */
    public ConsoleContext withAnalyst(ChatModel model) {
        this.analystModel = model;
        return this;
    }

    /** Wires the planner behind the "Plan stories" wizard ({@code roles.storyPlanner}). */
    public ConsoleContext withPlanner(ChatModel model) {
        this.plannerModel = model;
        return this;
    }

    /**
     * The model that reads documents and constructs the requirement graph.
     *
     * <p>Falls back to {@link #chatModel()} when unwired, which is not just defensiveness: until
     * this slot existed the wizard ran on the chat model, and every scripted-LLM test wires only
     * chat. The fallback is what makes those tests, and every config written before today, keep
     * exercising the same path.
     */
    public ChatModel analystModel() {
        return analystModel != null ? analystModel : chatModel;
    }

    /** The model that slices agreed checks into stories; falls back to {@link #chatModel()}. */
    public ChatModel plannerModel() {
        return plannerModel != null ? plannerModel : chatModel;
    }

    /** Selectable chat model names for the per-chat override picker (sc-app owns the list). */
    private Supplier<List<String>> chatModels = List::of;

    public ConsoleContext withChatModels(Supplier<List<String>> models) {
        this.chatModels = models == null ? List::of : models;
        return this;
    }

    public List<String> chatModels() {
        try {
            List<String> models = chatModels.get();
            return models == null ? List.of() : models;
        } catch (Exception e) {
            return List.of();
        }
    }

    public void setPushSink(BiConsumer<String, Object> sink) {
        this.pushSink = sink == null ? (topic, payload) -> { } : sink;
    }

    /** Broadcasts to the browser; a no-op until the Console engine connects. Never throws. */
    public void push(String topic, Object payload) {
        try {
            pushSink.accept(topic, payload);
        } catch (Exception ignored) {
            // push must never break the orchestrator; the store remains the source of truth
        }
    }

    public List<String> searchHistory(String query, int max) {
        return historySearch.apply(query, max);
    }

    /**
     * Works out how to build a folder of code, and saves the contract the operator settles on.
     *
     * <p>Two calls, because they cost wildly different things and must not be one button:
     * {@link #detect} runs the project's real build once, which can take minutes; {@link #save}
     * writes a file. The commands in that file are executed by the orchestrator on the host, so it
     * is written into the OPERATOR'S own checkout and never into a worker's worktree — a worker
     * able to edit it could rewrite the commands that judge it (§13.1).
     */
    public interface BuildContracts {

        /** Detects and (when {@code probe}) really runs the build; null when nothing is wired. */
        default com.swarmcoder.console.api.BuildContractDto detect(String path, boolean probe) {
            return null;
        }

        /** Writes the contract; "" on success, otherwise a message the operator can act on. */
        default String save(String path, String yaml, boolean overwrite) {
            return "error: this build cannot detect how to build a project.";
        }
    }

    /** Wires build-contract detection (owned by sc-app, which can see sc-verify). */
    public ConsoleContext withBuildContracts(BuildContracts contracts) {
        if (contracts != null) {
            this.buildContracts = contracts;
        }
        return this;
    }

    public BuildContracts buildContracts() {
        return buildContracts;
    }

    public String readSettings() {
        return settingsReader.get();
    }

    public String writeSettings(String yaml) {
        return settingsWriter.apply(yaml);
    }

    /**
     * Installs the wired context. Publishes the project registry immediately: this is the single
     * point at which the project bridge is known to be wired, and the shared signal RETAINS its
     * value, so a browser that connects afterwards is sent it on subscribe — the rail never has to
     * ask. (It is set before the server engine starts; {@code Signals.installTransport} replays the
     * registration, so the retained value survives that ordering.)
     */
    public static void set(ConsoleContext context) {
        instance = context;
        if (context != null) {
            ProjectPublisher.publish();
        ReadinessPublisher.publish(get());
        }
    }

    /**
     * The wiring every Console service reads on its first line.
     *
     * <p>The failure is a {@link com.zeroz4j.server.ClientVisibleException} rather than an
     * {@code IllegalStateException} because of what happens to each on the way to the browser.
     * Since ZeroZ Stack 0.7.0 an ordinary exception's message never leaves the server: the caller
     * is told "The server could not complete this request. Reference: 4f2a91cc" and the real text
     * goes to the log. That is right for a bug and wrong here, because this one has an answer the
     * person on the other end can act on — wait, or restart the console — and no amount of reading
     * the log will tell them that faster than the screen can.
     */
    public static ConsoleContext get() {
        ConsoleContext context = instance;
        if (context == null) {
            throw new com.zeroz4j.server.ClientVisibleException(
                "The console is not ready yet. Wait a moment and try again; if it keeps saying "
                + "this, the console process did not finish starting.");
        }
        return context;
    }

    public ArtifactStore store() {
        return store;
    }

    public TraceHub traceHub() {
        return traceHub;
    }

    public BlobStore blobStore() {
        return blobStore;
    }

    public UUID startRun(String goal, String kind) {
        refuseFromTheBrowserIfWatching("start a build");
        return intake.apply(goal, kind);
    }

    /**
     * Starts a run that is bound to a story BEFORE the workflow begins.
     *
     * <p>This exists because binding afterwards does not work. {@link #startRun(String, String)}
     * hands the run to the workflow engine, which starts advancing it on its own thread
     * immediately; by the time the caller has a run id, the engine already holds a Run whose
     * storyId is null, and every state transition rebuilds the Run from that copy. So the caller's
     * later {@code setStoryId} raced a run that had already left — and lost, silently, most of the
     * time. An unbound run designs its own requirements instead of the BRD's, plans without
     * criterion refs, and finishes without ever moving the story to REVIEW or stamping a single
     * criterion, which is the entire requirement-to-commit trace failing to happen.
     *
     * <p>Falls back to the two-step path when nothing has wired a story-aware starter, so a
     * ConsoleContext built before this existed keeps behaving as it did.
     */
    public UUID startRun(String goal, String kind, UUID storyId) {
        refuseFromTheBrowserIfWatching("start a build");
        if (storyRunStarter != null) {
            return storyRunStarter.start(goal, kind, storyId);
        }
        return intake.apply(goal, kind);
    }

    /** Starts a run already carrying its story. */
    @FunctionalInterface
    public interface StoryRunStarter {
        UUID start(String goal, String workflowKind, UUID storyId);
    }

    private StoryRunStarter storyRunStarter;

    /** Wires the story-aware run starter (owned by sc-app, which builds the Run). */
    public ConsoleContext withStoryRuns(StoryRunStarter starter) {
        this.storyRunStarter = starter;
        return this;
    }

    /** True when a run can be started already bound to its story. */
    public boolean bindsStoriesAtStart() {
        return storyRunStarter != null;
    }

    /**
     * Puts an accepted story's finished code on the project's delivery branch.
     *
     * <p>Wired by sc-app, for the reason every other seam here is: the console owns the backlog and
     * knows nothing about git or about running a build. What it means to deliver — merge into a
     * throwaway worktree, verify the combined tree, move the branch only if that is green — lives in
     * {@code StoryDelivery}, next to the integrator that already does the same thing one level down.
     *
     * @return null when the code is on the delivery branch; otherwise the plain-English reason it is
     *         not, which the operator reads and which stops the acceptance
     */
    @FunctionalInterface
    public interface StoryDeliverer {
        String deliver(UUID storyId);
    }

    private StoryDeliverer storyDeliverer;

    public ConsoleContext withStoryDelivery(StoryDeliverer deliverer) {
        this.storyDeliverer = deliverer;
        return this;
    }

    /**
     * Delivers the story, or returns why it could not be.
     *
     * <p>Returns null — "delivered" — when nothing has wired a deliverer. That is the shape a test
     * harness and a project with no repository both have, and refusing every acceptance in those
     * cases would break the whole backlog rather than protect anything.
     */
    public String deliverStory(UUID storyId) {
        return storyDeliverer == null ? null : storyDeliverer.deliver(storyId);
    }

    /** True when accepting a story really does put its code on the project's delivery branch. */
    public boolean deliversToBranch() {
        return storyDeliverer != null;
    }

    /**
     * Whether the operator has switched this session into unattended mode: the machine may accept a
     * story on its own when every check it claimed genuinely passed, and start the next one.
     *
     * <p>A supplier rather than a flag, because the setting lives in the config file that sc-app
     * owns and the operator can change it without restarting.
     */
    private java.util.function.BooleanSupplier unattended = () -> false;

    public ConsoleContext withUnattended(java.util.function.BooleanSupplier enabled) {
        this.unattended = enabled == null ? () -> false : enabled;
        return this;
    }

    public boolean unattendedMode() {
        try {
            return unattended.getAsBoolean();
        } catch (Exception e) {
            return false;   // a setting that cannot be read is not a licence to work unsupervised
        }
    }

    public void approveRun(UUID runId) {
        refuseFromTheBrowserIfWatching("approve a run");
        approve.accept(runId);
    }

    public void rejectRun(UUID runId) {
        refuseFromTheBrowserIfWatching("reject a run");
        reject.accept(runId);
    }

    // --- a console that may look but not act ----------------------------------------------------

    /**
     * Why this console only watches, or null for an ordinary console.
     *
     * <p><b>Why it exists (2026-09-25).</b> The end-to-end harness now serves this same Console over
     * its own temporary store, so the operator can watch a harness run in a browser instead of
     * reading its log. But the harness drives that run itself — it starts the story, it treats an
     * unanswered question as the run parking, and it measures what happened. A click in the browser
     * that started a second build, answered the question, or ran the analyst again would change the
     * thing being measured, and nothing in the verdict would say so.
     *
     * <p>The harness cannot simply leave its seams unwired, because it drives the run through the
     * very same services the browser calls: its own {@code BacklogServiceImpl.startSession} reaches
     * {@link #startRun(String, String, UUID)} exactly as a browser click does. What tells the two
     * apart is where the call came from. A browser call arrives through ZeroZ's RMI dispatcher, which
     * puts the WebSocket session on the calling thread ({@link com.zeroz4j.server.RmiRequestContext})
     * for the length of the call and clears it after; the harness's own calls, and every thread the
     * workflow starts, carry none. So a watch-only console refuses the acts below when a browser asks
     * for them, and lets the harness through.
     *
     * <p>What is refused is what acts on a run or starts model work: starting a run (every route —
     * the backlog, the chat, an ad-hoc story, a change request — ends in {@link #startRun}),
     * approving or rejecting one, answering a question, unattended mode, starting, answering or
     * restarting either wizard (each is model work on the one model server), turning a rule on or
     * off or changing its check, and creating or deleting a project. Editing a requirement or a
     * story by hand is NOT refused: that is ordinary store editing with a dozen entry points, not an
     * act on the run, and gating each of them was not worth what it would add to every service. A
     * watcher who edits the harness's requirements mid-walk has changed the measurement themselves.
     */
    private volatile String watchOnly;

    /**
     * Makes this a console that shows everything and changes nothing a browser asks it to change.
     *
     * @param why shown to the operator when a click is refused, so it says what is going on
     */
    public ConsoleContext watchOnly(String why) {
        this.watchOnly = why == null || why.isBlank() ? "this console is only watching" : why;
        return this;
    }

    /** Why this console only watches, or null when it is an ordinary one. */
    public String watchOnlyReason() {
        return watchOnly;
    }

    /**
     * Refuses {@code act} when this console only watches and the call came from a browser.
     *
     * <p>A {@link com.zeroz4j.server.ClientVisibleException}, so the operator reads the reason in
     * words rather than a reference code. Does nothing on an ordinary console, and nothing for a
     * call that did not come through the browser's RMI channel.
     */
    public void refuseFromTheBrowserIfWatching(String act) {
        String why = watchOnly;
        if (why != null && fromTheBrowser()) {
            throw new com.zeroz4j.server.ClientVisibleException(
                "You cannot " + act + " here: " + why + ".");
        }
    }

    /** Refuses {@code act} on the context that is installed, if any; see the instance method. */
    static void refuseIfWatching(String act) {
        ConsoleContext context = instance;
        if (context != null) {
            context.refuseFromTheBrowserIfWatching(act);
        }
    }

    /** True while the current thread is serving a browser's RMI call. */
    static boolean fromTheBrowser() {
        return com.zeroz4j.server.RmiRequestContext.getSessionId() != null;
    }
}
