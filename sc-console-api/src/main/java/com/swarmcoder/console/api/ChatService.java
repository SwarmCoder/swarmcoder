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
package com.swarmcoder.console.api;

import com.zeroz4j.api.RmiService;
import com.zeroz4j.api.Secured;

import java.util.List;

/**
 * The chat surface (CONSOLE_DESIGN_V2.md §5): multiple persisted chats per project;
 * {@code send} is fire-and-forget — the coder's reply streams back on the
 * {@code chat-stream} push topic and lands persisted on {@code chat-events}.
 */
@RmiService
@Secured
public interface ChatService {

    /** Creates a chat in the current project; returns its id. */
    String createChat(String title);

    /** Non-archived chats of the current project, newest first. */
    List<com.swarmcoder.domain.ChatSession> chats();

    /** Full transcript in sequence order. */
    List<com.swarmcoder.domain.ChatMessage> history(String chatId);

    /**
     * Sends a user message. Freeform text gets a streamed coder reply; {@code /run <goal>}
     * (also /bugfix and /refactor) starts a run bound to this chat, whose
     * lifecycle is narrated into the transcript. Returns "" or "error: ...".
     */
    String send(String chatId, String text);

    /** Cancels the in-flight coder reply, if any. */
    void stop(String chatId);

    /**
     * File addresses matching a typed @-mention fragment, for composer autocomplete. A blank
     * fragment returns the first few project files. Empty when no research tools are wired.
     */
    List<String> mentionCandidates(String chatId, String query);

    /** Attaches a pasted image (a data: URI) to the chat as a USER message. Returns "" or an error. */
    String sendImage(String chatId, String dataUri);

    void rename(String chatId, String title);

    void archive(String chatId);

    /** Copies a chat (transcript included, unbound from any run) into a new chat; returns its id. */
    String fork(String chatId);

    /** Selectable model names for the per-chat override picker (empty = only the project default). */
    List<String> availableModels();

    /** Sets a chat's model override (blank/null clears it back to the project default). */
    void setChatModel(String chatId, String model);

    /** A chat's current model override, or "" when it follows the project default. */
    String chatModel(String chatId);

    /**
     * Re-runs the last exchange: drops the most recent coder reply and answers the user message
     * again.
     *
     * <p>Deleting the previous answer is the point — leaving both would make the transcript the
     * model reads contain two answers to one question, and the next turn would be conditioned on a
     * reply the operator had already rejected.
     */
    String regenerate(String chatId);

    /**
     * The slash commands this build understands, for the composer's autocomplete: each entry is
     * {@code "/name<TAB>description"}.
     *
     * <p>Served rather than hardcoded in the client so a command added to the orchestrator cannot
     * silently go missing from the UI that is supposed to reveal it.
     */
    List<String> commands();
}


