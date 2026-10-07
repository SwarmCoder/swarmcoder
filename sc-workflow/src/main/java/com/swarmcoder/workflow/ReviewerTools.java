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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * The reviewer's own tool in a lookup session ({@link LookupAgent}): {@code report_done}, which
 * hands in the verdict it would otherwise have answered as one JSON reply. The lookups it shares
 * with the other roles (search, read_file, public_shape, find_usages, lookup_docs, ask_expert and
 * the rest) come from the session's toolbox; nothing here reads or writes a file.
 *
 * <p>The verdict is the same structured one: {@code approved}, and the objections, each in the
 * wording the review's own prompt asks for. What counts as an objection is unchanged - this class
 * only carries the answer.
 */
public final class ReviewerTools {

    private final ExpertTools session;
    private boolean handedIn;
    private boolean approved;
    private final List<String> objections = new ArrayList<>();

    ReviewerTools(ExpertTools session) {
        this.session = session;
    }

    /** Parameter names are the tool's schema (the build compiles with {@code -parameters}). */
    public String reportDone(String approved, String objections) {
        return session.runOwnTool(LookupAgent.SUBMIT_TOOL, approved, () -> {
            this.approved = approved != null && approved.strip().equalsIgnoreCase("true");
            this.objections.clear();
            if (objections != null) {
                for (String line : objections.split("\\R")) {
                    String objection = line.strip();
                    if (objection.startsWith("- ")) {
                        objection = objection.substring(2).strip();
                    }
                    if (!objection.isEmpty()) {
                        this.objections.add(objection);
                    }
                }
            }
            handedIn = true;
            return "verdict handed in";
        });
    }

    List<ToolBinding> bindings() {
        try {
            return List.of(new ToolBinding(LookupAgent.SUBMIT_TOOL,
                "Hand in your verdict and end the session. `approved` is \"true\" when you have "
                    + "no objection and \"false\" otherwise. `objections` is one objection per "
                    + "line, each in exactly the wording the instructions ask for, and empty when "
                    + "you approve.",
                this, ReviewerTools.class.getMethod("reportDone", String.class, String.class)));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean handedIn() {
        return handedIn;
    }

    DesignReviewerClient.Review review() {
        DesignReviewerClient.Review review = new DesignReviewerClient.Review();
        review.approved = approved;
        review.objections = List.copyOf(objections);
        return review;
    }
}
