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
package com.swarmcoder.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.console.api.AttentionItem;
import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.FlowView;
import com.swarmcoder.console.api.SupervisorService;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.Story;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tools an outside supervising model runs a whole project build with.
 *
 * <p><b>It adapts; it never decides.</b> Every tool is one call into {@link SupervisorService} or
 * {@link ControlService}, which in turn call the services a person's click reaches. There is no
 * model call anywhere behind these tools.
 *
 * <p><b>Written for a caller that pays per character.</b> The loop is: {@code wait_for_attention}
 * (blocks, costs nothing while it waits), read the one short item it returns, answer with the tool
 * the item names. A supervisor never needs a log, a listing or a second call to understand an item.
 *
 * <p>The tools that change something are named in {@link #WRITE_TOOLS}; the transport refuses each
 * of them without the installation's MCP secret.
 */
final class SupervisorMcpTools {

    /** Every tool here that changes something. The transport guards exactly these. */
    static final Set<String> WRITE_TOOLS = Set.of(
        "create_project", "switch_project", "add_document", "start_flow", "answer_flow_question",
        "submit_answers", "apply_proposals", "agree_requirements", "promote_story", "start_story",
        "accept_delivery", "send_back", "answer_question");

    private final SupervisorService supervisor;
    private final ControlService control;
    private final ObjectMapper json = new ObjectMapper();

    SupervisorMcpTools(SupervisorService supervisor, ControlService control) {
        this.supervisor = supervisor;
        this.control = control;
    }

    // --- registration ----------------------------------------------------------------------------

    /** The tools that only read. */
    List<SyncToolRegistration> readTools() {
        List<SyncToolRegistration> tools = new ArrayList<>();
        tools.add(SwarmMcpTools.tool("wait_for_attention",
            "SUPERVISING A BUILD: CALL THIS IN A LOOP. Blocks until something needs you (a "
                + "question, a delivery to judge, a stopped build) and returns that one item: "
                + "what is asked, the answers that will be acted on, the evidence, and the exact "
                + "tool call that answers it. Returns attention:null when the time ran out with "
                + "nothing needing you; call it again.",
            SwarmMcpTools.schema("""
                {"timeout_seconds": {"type":"integer","description":"How long to wait (default and max 100)."},
                 "skip": {"type":"integer","description":"Pass over this many items, most urgent first (default 0)."}}"""),
            args -> attention(supervisor.waitForAttention(
                SwarmMcpTools.num(args, "timeout_seconds", 0), SwarmMcpTools.num(args, "skip", 0)))));

        tools.add(SwarmMcpTools.tool("next_attention",
            "The one item that most needs you now, without waiting. Same reply as "
                + "wait_for_attention.",
            SwarmMcpTools.schema("""
                {"skip": {"type":"integer","description":"Pass over this many items, most urgent first (default 0)."}}"""),
            args -> attention(supervisor.nextAttention(SwarmMcpTools.num(args, "skip", 0)))));

        tools.add(SwarmMcpTools.tool("view_flow",
            "The analyst's flow (documents into requirements) or the planner's (requirements "
                + "into stories): its state, every question with its id and options, every "
                + "proposal with its id.",
            SwarmMcpTools.schema("""
                {"flow": {"type":"string","description":"analyst | planner"}}""", "flow"),
            args -> viewFlow(SwarmMcpTools.str(args, "flow"))));

        tools.add(SwarmMcpTools.tool("list_backlog",
            "Every story of the selected project: key, title, state, what it waits for. No "
                + "arguments.",
            SwarmMcpTools.schema(), args -> listBacklog()));

        tools.add(SwarmMcpTools.tool("decision_text",
            "The whole text of one question a build stopped to ask, a window at a time, with the "
                + "answers it accepts.",
            SwarmMcpTools.schema("""
                {"decision_id": {"type":"string"},
                 "offset": {"type":"integer","description":"Start character (default 0)."},
                 "max_chars": {"type":"integer","description":"Characters to return (default 4000)."}}""",
                "decision_id"),
            args -> decisionText(SwarmMcpTools.str(args, "decision_id"),
                SwarmMcpTools.num(args, "offset", 0), SwarmMcpTools.num(args, "max_chars", 4_000))));

        tools.add(SwarmMcpTools.tool("decision_log",
            "Everything the supervisor has decided for the selected project: what was asked, what "
                + "was answered, when. Newest last.",
            SwarmMcpTools.schema("""
                {"limit": {"type":"integer","description":"How many, newest (default 30, max 200)."}}"""),
            args -> decisionLog(SwarmMcpTools.num(args, "limit", 30))));
        return tools;
    }

    /** The tools that change something. Each needs the installation's MCP secret. */
    List<SyncToolRegistration> writeTools() {
        List<SyncToolRegistration> tools = new ArrayList<>();
        tools.add(SwarmMcpTools.tool("create_project",
            "CHANGES SOMETHING. Creates a project over a folder of code (or re-uses the one "
                + "already there) and selects it.",
            SwarmMcpTools.schema("""
                {"name": {"type":"string"},
                 "path": {"type":"string","description":"The folder the project's code lives in."},
                 "context_paths": {"type":"string","description":"Optional comma-separated read-only reference folders."}}""",
                "name", "path"),
            args -> createProject(SwarmMcpTools.str(args, "name"), SwarmMcpTools.str(args, "path"),
                SwarmMcpTools.str(args, "context_paths"))));

        tools.add(SwarmMcpTools.tool("switch_project",
            "CHANGES SOMETHING. Selects the project every other tool works on.",
            SwarmMcpTools.schema("""
                {"project_id": {"type":"string","description":"From list_projects."}}""",
                "project_id"),
            args -> {
                control.switchProject(SwarmMcpTools.str(args, "project_id"));
                return done("", "Selected.");
            }));

        tools.add(SwarmMcpTools.tool("add_document",
            "CHANGES SOMETHING. Adds pasted text to the selected project as a document for the "
                + "analyst to read. Set technical for a document that says how the system must "
                + "be built (stack, layout, what is forbidden) and not what it must do.",
            SwarmMcpTools.schema("""
                {"title": {"type":"string"},
                 "text": {"type":"string","description":"The document's text."},
                 "technical": {"type":"boolean","description":"True for a technical document (default false)."}}""",
                "title", "text"),
            args -> done(supervisor.addDocument(SwarmMcpTools.str(args, "title"),
                SwarmMcpTools.str(args, "text"), bool(args, "technical")),
                "Added. start_flow flow=analyst reads it.")));

        tools.add(SwarmMcpTools.tool("start_flow",
            "CHANGES SOMETHING. Starts the analyst (reads the documents into draft requirements) "
                + "or the planner (turns agreed requirements into stories). It takes minutes; "
                + "wait_for_attention returns when it has questions or proposals.",
            SwarmMcpTools.schema("""
                {"flow": {"type":"string","description":"analyst | planner"}}""", "flow"),
            args -> done(supervisor.startFlow(SwarmMcpTools.str(args, "flow")),
                "Started. Call wait_for_attention.")));

        tools.add(SwarmMcpTools.tool("answer_flow_question",
            "CHANGES SOMETHING. Answers one question of the analyst or the planner. An answer of "
                + "skip makes it state its own assumption. Answers take effect on submit_answers.",
            SwarmMcpTools.schema("""
                {"flow": {"type":"string","description":"analyst | planner"},
                 "question_id": {"type":"string"},
                 "answer": {"type":"string","description":"One of the options, your own text, or skip."},
                 "note": {"type":"string","description":"Optional qualification kept beside the answer."}}""",
                "flow", "question_id", "answer"),
            args -> done(supervisor.answerQuestion(SwarmMcpTools.str(args, "flow"),
                SwarmMcpTools.str(args, "question_id"), SwarmMcpTools.str(args, "answer"),
                SwarmMcpTools.str(args, "note")), "Recorded.")));

        tools.add(SwarmMcpTools.tool("submit_answers",
            "CHANGES SOMETHING. Submits the round of answers; the analyst or planner carries on.",
            SwarmMcpTools.schema("""
                {"flow": {"type":"string","description":"analyst | planner"}}""", "flow"),
            args -> done(supervisor.submitAnswers(SwarmMcpTools.str(args, "flow")),
                "Submitted. Call wait_for_attention.")));

        tools.add(SwarmMcpTools.tool("apply_proposals",
            "CHANGES SOMETHING. Writes what the analyst proposed into the requirements (as "
                + "drafts) or what the planner proposed into the backlog (as suggested stories).",
            SwarmMcpTools.schema("""
                {"flow": {"type":"string","description":"analyst | planner"},
                 "reject": {"type":"string","description":"Optional comma-separated proposal ids to leave out."}}""",
                "flow"),
            args -> done(supervisor.applyProposals(SwarmMcpTools.str(args, "flow"),
                SwarmMcpTools.str(args, "reject")), "Applied.")));

        tools.add(SwarmMcpTools.tool("agree_requirements",
            "CHANGES SOMETHING. A GATE A PERSON NORMALLY PASSES. Agrees drafted requirements as "
                + "scope and accepts their checks: what is agreed is what gets planned and built.",
            SwarmMcpTools.schema("""
                {"requirement": {"type":"string","description":"A handle such as R3, or all (default all)."}}"""),
            args -> done(supervisor.agreeRequirements(SwarmMcpTools.str(args, "requirement")),
                "Agreed.")));

        tools.add(SwarmMcpTools.tool("promote_story",
            "CHANGES SOMETHING. Marks a suggested story ready to build.",
            SwarmMcpTools.schema("""
                {"story": {"type":"string","description":"A key such as S3, or all."}}""", "story"),
            args -> done(supervisor.promoteStory(SwarmMcpTools.str(args, "story")),
                "Marked ready.")));

        tools.add(SwarmMcpTools.tool("start_story",
            "CHANGES SOMETHING. Starts a ready story's build. Not needed when supervised running "
                + "is switched on in the settings: the queue then starts each story itself.",
            SwarmMcpTools.schema("""
                {"story": {"type":"string","description":"A key such as S3."}}""", "story"),
            args -> startStory(SwarmMcpTools.str(args, "story"))));

        tools.add(SwarmMcpTools.tool("accept_delivery",
            "CHANGES SOMETHING. A GATE A PERSON NORMALLY PASSES. Accepts a story that came back "
                + "for a verdict: its code goes onto the delivery branch the next story is cut "
                + "from.",
            SwarmMcpTools.schema("""
                {"story": {"type":"string","description":"A key such as S3."}}""", "story"),
            args -> done(supervisor.acceptDelivery(SwarmMcpTools.str(args, "story")),
                "Accepted.")));

        tools.add(SwarmMcpTools.tool("send_back",
            "CHANGES SOMETHING. Sends a delivery, or a stopped story, back to be built again. The "
                + "note is what the next build is told was wrong.",
            SwarmMcpTools.schema("""
                {"story": {"type":"string","description":"A key such as S3."},
                 "note": {"type":"string","description":"Why, and what to do differently."}}""",
                "story", "note"),
            args -> done(supervisor.sendBack(SwarmMcpTools.str(args, "story"),
                SwarmMcpTools.str(args, "note")), "Sent back; it is ready to be built again.")));

        tools.add(SwarmMcpTools.tool("answer_question",
            "CHANGES SOMETHING. Answers a question a build stopped to ask AND hands the stopped "
                + "build back to its engine, which runs the stage it stopped in again. The reply "
                + "says whether the build was restarted.",
            SwarmMcpTools.schema("""
                {"decision_id": {"type":"string"},
                 "answer": {"type":"string","description":"One of the answer tokens the question lists."},
                 "text": {"type":"string","description":"Free text, where the answer takes it."}}""",
                "decision_id", "answer"),
            args -> done(supervisor.answerDecision(SwarmMcpTools.str(args, "decision_id"),
                SwarmMcpTools.str(args, "answer"), SwarmMcpTools.str(args, "text")), "Recorded.")));
        return tools;
    }

    // --- replies ---------------------------------------------------------------------------------

    /** One attention item as a reply, or the standing sentence when there is none. */
    String attention(AttentionItem item) {
        ObjectNode root = json.createObjectNode();
        if (item == null) {
            root.putNull("attention");
            root.put("standing", supervisor.standing());
            return root.toString();
        }
        root.put("kind", item.kind());
        root.put("project", item.project());
        if (!item.story().isEmpty()) {
            root.put("story", item.story());
        }
        root.put("question", item.question());
        if (!item.options().isEmpty()) {
            ArrayNode options = root.putArray("options");
            item.options().forEach(options::add);
        }
        if (!item.evidence().isEmpty()) {
            root.put("evidence", item.evidence());
        }
        if (!item.answerWith().isEmpty()) {
            root.put("answerWith", item.answerWith());
        }
        return root.toString();
    }

    String viewFlow(String flow) {
        FlowView view = supervisor.flow(flow);
        if (view == null || view.flow() == null) {
            return problem("No project is selected, so there is no flow to show.");
        }
        ObjectNode root = json.createObjectNode();
        root.put("state", String.valueOf(view.flow().state()));
        if (!nn(view.flow().stepLabel()).isEmpty()) {
            root.put("doing", view.flow().stepLabel());
        }
        if (!nn(view.flow().error()).isEmpty()) {
            root.put("error", ReplyBudget.snippet(view.flow().error()));
        }
        if (!view.documents().isEmpty()) {
            ArrayNode documents = root.putArray("documents");
            view.documents().forEach(document -> documents.add(nn(document.filename())));
        }
        if (view.unclaimedCriteria() > 0) {
            root.put("agreedChecksNoStoryCovers", view.unclaimedCriteria());
        }
        ArrayNode questions = root.putArray("questions");
        for (FlowQuestion question : view.questions()) {
            ObjectNode node = questions.addObject();
            node.put("id", String.valueOf(question.id()));
            if (!nn(question.subject()).isEmpty()) {
                node.put("about", question.subject());
            }
            node.put("text", nn(question.text()));
            if (question.options() != null && !question.options().isEmpty()) {
                ArrayNode options = node.putArray("options");
                question.options().forEach(options::add);
            }
            if (question.skipped()) {
                node.put("skipped", true);
            } else if (!nn(question.answer()).isEmpty()) {
                node.put("answer", question.answer());
            }
        }
        ArrayNode proposals = root.putArray("proposals");
        for (FlowProposal proposal : view.proposals()) {
            ObjectNode node = proposals.addObject();
            node.put("id", String.valueOf(proposal.id()));
            node.put("kind", String.valueOf(proposal.kind()));
            node.put("handle", nn(proposal.handle()));
            node.put("title", nn(proposal.title()));
            node.put("text", ReplyBudget.snippet(proposal.after()));
            if (!nn(proposal.impact()).isEmpty()) {
                node.put("impact", ReplyBudget.snippet(proposal.impact()));
            }
        }
        return ReplyBudget.clampReply(root.toString(), "nothing (from view_flow)");
    }

    String listBacklog() {
        Backlog backlog = supervisor.backlog();
        if (backlog == null) {
            return problem("No project is selected.");
        }
        Map<UUID, String> keys = new HashMap<>();
        backlog.stories().forEach(story -> keys.put(story.id(), story.key()));
        ObjectNode root = json.createObjectNode();
        ArrayNode rows = root.putArray("stories");
        List<Story> stories = new ArrayList<>(backlog.stories());
        stories.sort((a, b) -> a.order() != b.order() ? Integer.compare(a.order(), b.order())
            : nn(a.key()).compareTo(nn(b.key())));
        for (Story story : stories) {
            ObjectNode node = rows.addObject();
            node.put("key", nn(story.key()));
            node.put("title", nn(story.title()));
            node.put("state", story.state() == null ? "" : story.state().label());
            List<String> after = new ArrayList<>();
            if (story.dependsOn() != null) {
                story.dependsOn().forEach(id -> after.add(keys.getOrDefault(id, "?")));
            }
            if (!after.isEmpty()) {
                node.put("buildsOn", String.join(",", after));
            }
            if (!nn(story.waitingReason()).isEmpty()) {
                node.put("waiting", ReplyBudget.snippet(story.waitingReason()));
            }
            if (!nn(story.acceptedBy()).isEmpty()) {
                node.put("acceptedBy", story.acceptedBy());
            }
        }
        root.put("questionsFromBuilds", backlog.decisions().size());
        return ReplyBudget.clampReply(root.toString(), "nothing (from list_backlog)");
    }

    String decisionText(String decisionId, int offset, int maxChars) {
        String whole = supervisor.decisionText(decisionId);
        if (whole == null || whole.isEmpty()) {
            return problem("There is no question with that id.");
        }
        ReplyBudget.Window window = ReplyBudget.window(whole, offset,
            maxChars <= 0 ? 4_000 : maxChars);
        ObjectNode root = json.createObjectNode();
        root.put("totalChars", window.total());
        root.put("text", window.text());
        if (window.more()) {
            root.put("nextOffset", window.nextOffset());
        }
        ArrayNode options = root.putArray("answers");
        supervisor.decisionOptions(decisionId).forEach(options::add);
        return root.toString();
    }

    String decisionLog(int limit) {
        List<AutonomousDecision> all = supervisor.decisionLog();
        int keep = ReplyBudget.rows(limit, 30);
        List<AutonomousDecision> shown = all.subList(Math.max(0, all.size() - keep), all.size());
        ObjectNode root = json.createObjectNode();
        root.put("decided", all.size());
        root.put("shown", shown.size());
        ArrayNode rows = root.putArray("decisions");
        for (AutonomousDecision decision : shown) {
            ObjectNode node = rows.addObject();
            node.put("at", decision.at() == null ? "" : decision.at().toString());
            node.put("what", decision.headline());
            node.put("about", nn(decision.subject()));
            node.put("asked", ReplyBudget.snippet(decision.question()));
            node.put("answered", ReplyBudget.snippet(decision.answer()));
        }
        return ReplyBudget.clampReply(root.toString(), "a smaller limit (from decision_log)");
    }

    private String createProject(String name, String path, String contextPaths) {
        if (name.isBlank() || path.isBlank()) {
            return problem("create_project needs a name and a path.");
        }
        String id = control.createProject(name, path, contextPaths);
        if (id != null && id.startsWith("error:")) {
            return problem(id.substring("error:".length()).strip());
        }
        control.switchProject(id);
        ObjectNode root = json.createObjectNode();
        root.put("done", true);
        root.put("projectId", nn(id));
        root.put("note", "Created and selected. add_document loads what it should build.");
        return root.toString();
    }

    private String startStory(String story) {
        String result = supervisor.startStory(story);
        if (result != null && result.startsWith("error:")) {
            return problem(result.substring("error:".length()).strip());
        }
        ObjectNode root = json.createObjectNode();
        root.put("done", true);
        root.put("runId", nn(result));
        root.put("note", "Building. Call wait_for_attention.");
        return root.toString();
    }

    /** A mutation's reply: the refusal in words, or done with what the service said. */
    private String done(String result, String fallbackNote) {
        if (result != null && result.startsWith("error:")) {
            return problem(result.substring("error:".length()).strip());
        }
        ObjectNode root = json.createObjectNode();
        root.put("done", true);
        root.put("note", result == null || result.isBlank() ? fallbackNote : result);
        return root.toString();
    }

    private String problem(String plainEnglish) {
        ObjectNode root = json.createObjectNode();
        root.put("problem", plainEnglish);
        return root.toString();
    }

    private static boolean bool(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Boolean flag ? flag : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
