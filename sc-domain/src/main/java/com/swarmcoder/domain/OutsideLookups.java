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
package com.swarmcoder.domain;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The one switch for everything the application sends to a host that is neither a model endpoint
 * from the settings file nor this computer itself: the documentation server, the Researcher's
 * web search and page fetch, other MCP servers, documentation sites, and what a page under test
 * may load in the headless browser.
 *
 * <p><b>Off unless the settings file says {@code outsideLookups: true}.</b> A project may be
 * private, and the words of a lookup are words about the project: a library name out of its build
 * files, a class name a model put in a question. Nobody should have to learn after a build that
 * those went somewhere.
 *
 * <p><b>On, every sending is written down</b> through {@link #sent}: where it went and the words
 * that went, never what came back. That line is the audit trail.
 *
 * <p>An instance is handed to each component that can send. {@link #process()} is the same
 * decision for the few places no instance can reach (the browser check builds its own browser);
 * it is process state like {@link HostExecution}, is never persisted, and is off until the
 * application sets it at startup.
 */
public final class OutsideLookups {

    /** How many sendings are remembered for a report; the log keeps all of them. */
    private static final int REMEMBERED = 500;

    private static volatile OutsideLookups process = off();

    private final boolean enabled;
    private final Consumer<String> audit;
    private final List<String> sent = new CopyOnWriteArrayList<>();

    private OutsideLookups(boolean enabled, Consumer<String> audit) {
        this.enabled = enabled;
        this.audit = audit == null ? line -> { } : audit;
    }

    /** Nothing is sent anywhere but the model endpoints and this computer. */
    public static OutsideLookups off() {
        return new OutsideLookups(false, null);
    }

    /** @param audit where each sending is written, one line each - the application's log */
    public static OutsideLookups on(Consumer<String> audit) {
        return new OutsideLookups(true, audit);
    }

    /** The decision the running application was started with; off until it says otherwise. */
    public static OutsideLookups process() {
        return process;
    }

    /** Set once at startup by the application; a test that sets it puts {@link #off()} back. */
    public static void process(OutsideLookups decided) {
        process = decided == null ? off() : decided;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Writes down one sending BEFORE it is made. A caller that is switched off must not call
     * this and must not send; calling it while off is recorded as a refusal, so a mistake shows.
     *
     * @param channel which component, in words an owner reads ("documentation server")
     * @param address where it goes; only its host is of interest and it never carries a key
     * @param words   what is sent - the query, never an answer and never a credential
     * @return whether the caller may send
     */
    public boolean sent(String channel, String address, String words) {
        if (!enabled) {
            audit.accept("Outside lookups are off: nothing was sent to " + hostOf(address) + " ("
                + channel + ").");
            return false;
        }
        String line = "Outside lookup sent to " + hostOf(address) + " (" + channel + "): " + words;
        if (sent.size() < REMEMBERED) {
            sent.add(line);
        }
        audit.accept(line);
        return true;
    }

    /** What was sent so far in this process, oldest first, at most {@value #REMEMBERED} lines. */
    public List<String> sentSoFar() {
        return List.copyOf(sent);
    }

    /**
     * What the log says once at startup.
     *
     * @param channels     every component the switch governs, as the owner would name it
     * @param settingsFile where the switch is written
     */
    public String startLine(List<String> channels, String settingsFile) {
        String list = String.join("; ", channels == null ? List.<String>of() : channels);
        return enabled
            ? "Outside lookups are ON: words taken from the project can leave this computer "
                + "through " + list + ". Each sending is logged as \"Outside lookup sent to "
                + "<host>\" with the words that went. Remove `outsideLookups: true` from "
                + settingsFile + " to stop them."
            : "Outside lookups are off: nothing is sent to " + list + ". The application contacts "
                + "only the model endpoints in your settings and this computer itself. Put "
                + "`outsideLookups: true` in " + settingsFile + " to allow them.";
    }

    /**
     * Whether an address is this computer: {@code localhost}, a name under {@code .localhost}, an
     * IPv4 address in 127.0.0.0/8 or the IPv6 loopback. Decided from the text alone - no name is
     * looked up, since a lookup is itself a sending.
     */
    public static boolean isLoopback(String address) {
        String host = hostOf(address).toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        if (host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("127")) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** The host of an address, or the text itself when it is not a URL. Never the path or query. */
    public static String hostOf(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String text = address.strip();
        try {
            URI uri = URI.create(text.contains("://") ? text : "x://" + text);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException e) {
            // not a URL; fall through to the text up to its first separator
        }
        int scheme = text.indexOf("://");
        String rest = scheme < 0 ? text : text.substring(scheme + 3);
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return rest.substring(0, end);
    }
}
