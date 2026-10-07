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

import com.swarmcoder.console.api.ChatService;
import com.swarmcoder.domain.ChatSession;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.swarmcoder.domain.ChatMessage;

@ApplicationScoped
public class ChatServiceImpl implements ChatService {

    /**
     * How many messages of a conversation travel to the browser at once. A chat this long is
     * already past the point where scrolling it is how anybody finds anything.
     */
    static final int MAX_HISTORY_MESSAGES = 400;

    @Override
    public String createChat(String title) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = new ChatSession(UUID.randomUUID(), context.currentProjectId(),
            title == null || title.isBlank() ? "New chat" : title, Instant.now(), null, false);
        context.store().saveChat(chat);
        context.push("chat-list", chat); // sidebar lists refresh live (domain object on the wire)
        return chat.id().toString();
    }

    @Override
    public List<ChatSession> chats() {
        ConsoleContext context = ConsoleContext.get();
        return context.store().listChats(context.currentProjectId());
    }

    /**
     * The most recent {@link #MAX_HISTORY_MESSAGES} messages of one conversation, oldest first.
     *
     * <p>It used to be the whole transcript with no limit. Since 0.7.0 a single reply over 4 MB
     * closes the connection instead of returning, and a chat message carries unbounded prose —
     * pasted diffs, whole tool outputs, a model's long answer. A months-old conversation was
     * therefore a chat that could no longer be opened at all, with the console blanking and
     * reconnecting instead of saying so.
     *
     * <p>Nothing is deleted: the whole transcript stays in the store and the model still gets it.
     */
    @Override
    public List<ChatMessage> history(String chatId) {
        List<ChatMessage> all = ConsoleContext.get().store()
            .chatTranscript(UUID.fromString(chatId));
        if (all == null || all.size() <= MAX_HISTORY_MESSAGES) {
            return all;
        }
        return new java.util.ArrayList<>(
            all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size()));
    }

    @Override
    public String send(String chatId, String text) {
        return ChatOrchestrator.get().send(UUID.fromString(chatId), text);
    }

    @Override
    public void stop(String chatId) {
        ChatOrchestrator.get().stop(UUID.fromString(chatId));
    }

    @Override
    public List<String> mentionCandidates(String chatId, String query) {
        ConsoleContext.ChatTools tools = ConsoleContext.get().chatTools();
        return tools == null ? List.of() : tools.mentionCandidates(query);
    }

    @Override
    public String sendImage(String chatId, String dataUri) {
        return ChatOrchestrator.get().sendImage(UUID.fromString(chatId), dataUri);
    }

    @Override
    public void rename(String chatId, String title) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(UUID.fromString(chatId));
        if (chat != null && title != null && !title.isBlank()) {
            chat.setTitle(title);
            context.store().saveChat(chat);
        }
    }

    @Override
    public void archive(String chatId) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(UUID.fromString(chatId));
        if (chat != null) {
            chat.setArchived(true);
            context.store().saveChat(chat);
        }
    }

    @Override
    public String fork(String chatId) {
        ConsoleContext context = ConsoleContext.get();
        UUID sourceId = UUID.fromString(chatId);
        ChatSession source = context.store().root().chats().get(sourceId);
        if (source == null) {
            return "error: unknown chat " + chatId;
        }
        ChatSession copy = new ChatSession(UUID.randomUUID(), source.projectId(),
            (source.title() == null || source.title().isBlank() ? "Chat" : source.title()) + " (fork)",
            Instant.now(), null, false);
        copy.setModelOverride(source.modelOverride());
        context.store().saveChat(copy);
        // Copy the transcript verbatim, unbound from the original run/decisions.
        for (ChatMessage message : context.store().chatTranscript(sourceId)) {
            context.store().appendChatMessage(new ChatMessage(
                UUID.randomUUID(), copy.id(), 0, message.role(), message.kind(),
                message.markdown(), null, null, message.at(), message.tokens()));
        }
        context.push("chat-list", copy);
        return copy.id().toString();
    }

    @Override
    public List<String> availableModels() {
        return ConsoleContext.get().chatModels();
    }

    @Override
    public void setChatModel(String chatId, String model) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(UUID.fromString(chatId));
        if (chat != null) {
            chat.setModelOverride(model == null || model.isBlank() ? null : model);
            context.store().saveChat(chat);
        }
    }

    @Override
    public String chatModel(String chatId) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(UUID.fromString(chatId));
        return chat == null || chat.modelOverride() == null ? "" : chat.modelOverride();
    }

    @Override
    public String regenerate(String chatId) {
        return ChatOrchestrator.get().regenerate(UUID.fromString(chatId));
    }

    @Override
    public List<String> commands() {
        return ChatOrchestrator.commands();
    }
}
