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
package com.swarmcoder.console.ui;

import com.swarmcoder.console.api.ChatStreamDto;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.ChatSession;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/**
 * Client-side chat state (design §4.1, chat slice): push deltas land here once, every view
 * renders from these signals. Push callbacks arrive on the WS thread — TeaVM is
 * single-threaded in the browser, so plain signal writes are safe.
 */
final class ChatStore {

    /** Sidebar list; refreshed via RMI and nudged by the chat-list push topic (domain objects). */
    static final ValueSignal<List<ChatSession>> chats = new ValueSignal<>(new ArrayList<>());

    private static final Map<String, ValueSignal<List<ChatMessage>>> transcripts = new HashMap<>();
    private static final Map<String, ValueSignal<String>> streams = new HashMap<>();

    private ChatStore() {}

    static ValueSignal<List<ChatMessage>> transcript(String chatId) {
        return transcripts.computeIfAbsent(chatId, id -> new ValueSignal<>(new ArrayList<>()));
    }

    /** The coder's in-flight reply text; "" when idle. */
    static ValueSignal<String> stream(String chatId) {
        return streams.computeIfAbsent(chatId, id -> new ValueSignal<>(""));
    }

    /** chat-events push: a persisted message — append (or replace by id) in its transcript. */
    static void onMessage(ChatMessage message) {
        ValueSignal<List<ChatMessage>> signal = transcript(message.getChatId().toString());
        List<ChatMessage> next = new ArrayList<>(signal.get());
        next.removeIf(m -> m.getId().equals(message.getId()));
        next.add(message);
        next.sort(Comparator.comparingInt(ChatMessage::getSeq));
        signal.set(next);
    }

    /** chat-stream push: streaming text for the in-flight coder reply. */
    static void onStream(ChatStreamDto delta) {
        stream(delta.getChatId()).set(delta.isDone() ? "" : delta.getText());
    }

    static void onChatListChanged(ChatSession created) {
        List<ChatSession> next = new ArrayList<>(chats.get());
        next.removeIf(c -> c.getId().equals(created.getId()));
        next.add(0, created);
        chats.set(next);
    }
}
