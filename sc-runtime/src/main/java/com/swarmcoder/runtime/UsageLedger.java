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
package com.swarmcoder.runtime;

import com.swarmcoder.domain.AskedQuestions;
import com.swarmcoder.domain.CallLog;
import com.swarmcoder.domain.CallUsageRecord;
import com.swarmcoder.domain.LookupUsageRecord;
import com.swarmcoder.domain.ModelUsageRecord;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunUsageRecord;
import com.swarmcoder.inference.LookupMeter;
import com.swarmcoder.inference.RunMeter;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The application's record of what every model call cost: per run, per role, per task, per model
 * and per class of server, from the {@code usage} the server returned with each call.
 *
 * <p><b>Why it exists</b> (2026-10-13). A supervised build whose planning roles ran on a paid
 * model was reported as "1163749 in, 2369 out". Its architect alone had made 51 calls. The
 * figure came from the cloud gate, which was charged estimates (characters / 4) by the roles
 * that ask one question, and by the roles that work as agents with tools only the growth of
 * their conversation's size, all of it as input. The server's own counts were read for every
 * call and then kept only while the test harness had its meter switched on.
 *
 * <p><b>What it does.</b> It is the {@link RunMeter.Recorder}: every model call of the process
 * ends here, a session's calls one by one. Each call is added to the row of its run, role, task,
 * model and class of server ({@link ModelUsageRecord}); each lookup to the row of its run, role
 * and kind. The rows are stored with the run ({@link RunUsageRecord}). A call to a paid server
 * is also handed to the {@link CloudGate} with its true input and output, which is what the
 * budgets are held against once {@link CloudGate#countFromUsage()} is set.
 *
 * <p><b>Paid or local.</b> Whoever builds the ledger says which server addresses are the
 * operator's own; a call to any other is paid. The application's rule: the servers its worker
 * models are configured on (worker models cost nothing), and any address on this machine or on
 * a private network ({@link #onThisNetwork}). It is asked of the call's address, not of the
 * role, so a planning role put on the local server is counted as local, and only calls to paid
 * servers are held against the cloud budgets.
 *
 * <p><b>Whose call.</b> The run, story and project are the ones the calling thread is working
 * for ({@link CloudGate#current()}); a session that knows its run says so. A call made for no
 * run - a wizard, the chat, knowledge read at start - is kept with the project the program has
 * open ({@link #forCallsOutsideRuns}) and stored like a run's, under that project's id
 * ({@link #outsideRunsOf}). Until section 102 such calls were stored nowhere.
 *
 * <p><b>Each call, and each question.</b> Beside the sums per role a run's record keeps every
 * call one by one ({@link CallLog}: its input, cached input, output and thinking tokens and
 * what it was for) and the questions its lookups have answered, so that a lookup that asks
 * what was already answered in this run - to this role or another - is counted as a repeat.
 *
 * <p><b>A server that reports no usage.</b> The input is then estimated from the size of the
 * request and the output from the size of the answer (characters / 4), and the row says how many
 * of its calls were counted that way.
 */
public final class UsageLedger implements RunMeter.Recorder {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);
    /** Local calls are written to the store at most this often; paid calls at once. */
    private static final long FLUSH_MILLIS = 5_000;

    /** The key of what was called for no run while no project was open. */
    private static final UUID NO_PROJECT = new UUID(0, 0);

    /** One run's rows while the process lives, or what one project called for no run. */
    private static final class Book {
        final boolean outsideRuns;
        UUID storyId;
        UUID projectId;
        final Map<String, ModelUsageRecord> models = new LinkedHashMap<>();
        final Map<String, LookupUsageRecord> lookups = new LinkedHashMap<>();
        CallLog calls = new CallLog();
        AskedQuestions asked = new AskedQuestions();
        final Set<Long> answered = new HashSet<>();

        Book(boolean outsideRuns) {
            this.outsideRuns = outsideRuns;
        }
    }

    private final CloudGate gate;
    private final ArtifactStore store;
    private final Predicate<String> localEndpoint;
    private final Object lock = new Object();
    /** By run id, and by project id for what was called for no run; read from the store on first use. */
    private final Map<UUID, Book> books = new LinkedHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastFlush = new AtomicLong();
    private ScheduledExecutorService flusher;
    private volatile Supplier<UUID> openProject = () -> null;

    /**
     * @param gate          charged with every paid call's true input and output; may be null
     * @param store         where each run's rows are kept, and read from when a run is first
     *                      added to or asked for; null keeps them in memory only
     * @param localEndpoint true for the base address ({@link #normalized}) of a server the
     *                      operator runs, whose tokens cost nothing; null for "every server is
     *                      paid"
     */
    public UsageLedger(CloudGate gate, ArtifactStore store, Predicate<String> localEndpoint) {
        this.gate = gate;
        this.store = store;
        this.localEndpoint = localEndpoint == null ? address -> false : localEndpoint;
    }

    /**
     * Says which project a call made for no run belongs to: the one the program has open when
     * the call is made. Without it, and while it answers null, such calls are kept together
     * under no project.
     */
    public UsageLedger forCallsOutsideRuns(Supplier<UUID> projectOpenNow) {
        this.openProject = projectOpenNow == null ? () -> null : projectOpenNow;
        return this;
    }

    /** Starts writing what local calls added every few seconds. Without it, see {@link #flush()}. */
    public synchronized UsageLedger flushingInTheBackground() {
        if (store != null && flusher == null) {
            flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "usage-ledger-flush");
                thread.setDaemon(true);
                return thread;
            });
            flusher.scheduleWithFixedDelay(this::flush, FLUSH_MILLIS, FLUSH_MILLIS,
                TimeUnit.MILLISECONDS);
        }
        return this;
    }

    // --- recording ------------------------------------------------------------------------------

    @Override
    public Object begins(RunMeter.Tag tag, String run) {
        CloudGate.Where here = CloudGate.current();
        if (here != null && here.runId() != null) {
            return here;
        }
        UUID runId = parse(run);
        return runId != null ? whereOf(runId) : outsideRuns(here);
    }

    /** Whose a call or a lookup for no run is: the project the thread names, or the open one. */
    private CloudGate.Where outsideRuns(CloudGate.Where here) {
        if (here != null && here.projectId() != null) {
            return here;
        }
        UUID project = null;
        try {
            project = openProject.get();
        } catch (RuntimeException e) {                                     // noqa
            // Counted under no project, which is still counted.
        }
        return new CloudGate.Where(project, here == null ? null : here.storyId(), null);
    }

    @Override
    public void finished(Object begun, RunMeter.Finished finished) {
        CloudGate.Where where = begun instanceof CloudGate.Where known ? known
            : outsideRuns(null);
        RunMeter.Call call = finished.call();
        boolean paid = !isLocal(finished.endpoint());
        long input = call.promptTokens();
        long output = call.completionTokens();
        long thinking = finished.reasoningTokens();
        boolean estimated = false;
        boolean thinkingEstimated = false;
        if (!call.failed()) {
            if (input < 0 && call.requestChars() > 0) {
                input = Math.max(1, call.requestChars() / 4);
                estimated = true;
            }
            if (output < 0 && finished.answerChars() >= 0) {
                output = Math.max(1, finished.answerChars() / 4);
                estimated = true;
            }
            if (thinking < 0 && finished.thoughtChars() > 0) {
                thinking = Math.max(1, finished.thoughtChars() / 4);
                thinkingEstimated = true;
            }
        }
        String endpointClass = paid ? ModelUsageRecord.CLOUD : ModelUsageRecord.LOCAL;
        String purpose = finished.purpose() == null || finished.purpose().isBlank()
            ? call.role() : finished.purpose();
        UUID key;
        synchronized (lock) {
            Book book = bookOf(where);
            key = keyOf(where);
            ModelUsageRecord row = book.models.computeIfAbsent(
                keyOf(call.role(), call.task(), call.model(), endpointClass),
                k -> new ModelUsageRecord(call.role(), call.task(), call.model(), endpointClass));
            row.addCall(input, output, call.cachedPromptTokens(), call.millis(), call.failed(),
                estimated);
            row.addThinking(thinking, thinkingEstimated);
            // The request's own size beside the server's count of it, and how much of it
            // repeated the request before: only where the server's count is its own.
            row.addRequest(estimated ? -1 : call.promptTokens(), call.requestChars(),
                call.samePrefixChars());
            book.calls.add(new CallUsageRecord(call.role(), call.task(), call.worker(),
                call.model(), endpointClass, purpose, finished.candidate(), call.startMillis(),
                call.millis(), input, input < 0 ? -1 : call.cachedPromptTokens(), output,
                thinking, call.failed(), estimated || thinkingEstimated));
        }
        if (paid && gate != null) {
            try {
                // A call for no run is counted by the gate as it always was: outside every run.
                gate.recordUsage(where.runId() == null ? null : where, Math.max(0, input),
                    Math.max(0, output));
            } catch (RuntimeException e) {
                log.warn("A paid call's tokens could not be counted against the budget: {}",
                    e.toString());
            }
        }
        changed(key, paid);
    }

    @Override
    public void lookup(String role, LookupMeter.Kind kind, int chars) {
        lookup(role, kind, chars, false, null);
    }

    @Override
    public void lookup(String role, LookupMeter.Kind kind, int chars, boolean reference) {
        lookup(role, kind, chars, reference, null);
    }

    @Override
    public void lookup(String role, LookupMeter.Kind kind, int chars, boolean reference,
                       String question) {
        CloudGate.Where here = CloudGate.current();
        CloudGate.Where where = here != null && here.runId() != null ? here : outsideRuns(here);
        UUID key;
        synchronized (lock) {
            Book book = bookOf(where);
            key = keyOf(where);
            LookupUsageRecord row = book.lookups.computeIfAbsent(
                role + "\u0000" + kind.name(),
                k -> new LookupUsageRecord(role, kind.name(), kind.label(), kind.structured(),
                    0, 0));
            row.add(1, chars);
            if (reference) {
                row.addReference(1, chars);
            }
            if (question != null && !question.isBlank()) {
                long hash = AskedQuestions.hashOf(question);
                if (book.answered.add(hash)) {
                    book.asked.add(hash);
                } else {
                    row.addRepeat(1, chars);
                }
            }
        }
        changed(key, false);
    }

    private static UUID keyOf(CloudGate.Where where) {
        if (where != null && where.runId() != null) {
            return where.runId();
        }
        return where == null || where.projectId() == null ? NO_PROJECT : where.projectId();
    }

    /** The book of a run, or of what a project called for no run; read from the store once. */
    private Book bookOf(CloudGate.Where where) {
        boolean outsideRuns = where == null || where.runId() == null;
        Book book = bookAt(keyOf(where), outsideRuns);
        if (book.storyId == null && !outsideRuns) {
            book.storyId = where.storyId();
        }
        if (book.projectId == null && where != null) {
            book.projectId = where.projectId();
        }
        return book;
    }

    private Book bookAt(UUID key, boolean outsideRuns) {
        Book book = books.get(key);
        if (book != null) {
            return book;
        }
        book = new Book(outsideRuns);
        RunUsageRecord record = null;
        if (store != null) {
            try {
                record = store.root().runUsage().get(key);
            } catch (RuntimeException e) {                                 // noqa
                // Counting goes on from nothing; what was stored stays as it is until written.
            }
        }
        if (record != null) {
            book.storyId = record.storyId();
            book.projectId = record.projectId();
            for (ModelUsageRecord row : record.models()) {
                book.models.put(keyOf(row.role(), row.task(), row.model(), row.endpointClass()),
                    row.copy());
            }
            for (LookupUsageRecord row : record.lookups()) {
                book.lookups.put(row.role() + "\u0000" + row.kind(), row.copy());
            }
            if (record.callLog() != null) {
                book.calls = record.callLog().continued();
            }
            if (record.asked() != null) {
                book.asked = record.asked().continued();
                for (long hash : book.asked.all()) {
                    book.answered.add(hash);
                }
            }
        }
        books.put(key, book);
        return book;
    }

    private CloudGate.Where whereOf(UUID runId) {
        UUID storyId = null;
        UUID projectId = null;
        if (store != null) {
            try {
                Run run = store.root().runs.get(runId);
                if (run != null) {
                    storyId = run.storyId();
                    projectId = run.projectId();
                }
            } catch (RuntimeException e) {                                 // noqa
                // The run's own id is enough to count the call; the story and project stay unknown.
            }
        }
        return new CloudGate.Where(projectId, storyId, runId);
    }

    private static UUID parse(String run) {
        if (run == null || run.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(run);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String keyOf(String role, String task, String model, String endpointClass) {
        return role + "\u0000" + task + "\u0000" + model + "\u0000" + endpointClass;
    }

    // --- paid or local --------------------------------------------------------------------------

    /** True when a call to {@code endpoint} costs nothing; false for a call that named none. */
    public boolean isLocal(String endpoint) {
        String address = normalized(endpoint);
        if (address.isEmpty()) {
            return false;
        }
        try {
            return localEndpoint.test(address);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A base address as it is compared: lower case, no trailing slash, no trailing "/v1". */
    public static String normalized(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String url = baseUrl.trim().toLowerCase(Locale.ROOT);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/v1") ? url.substring(0, url.length() - 3) : url;
    }

    /**
     * True for an address on this machine or on a private network, read from the address as it
     * is written: a name is never looked up, so a server reached by name is only local when its
     * address was configured as a worker's.
     */
    public static boolean onThisNetwork(String address) {
        String host;
        try {
            host = URI.create(address).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        String literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        boolean numeric = literal.indexOf(':') >= 0
            || literal.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
        if (!numeric) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(literal); // a literal: nothing is looked up
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress()
                || parsed.isLinkLocalAddress() || parsed.isAnyLocalAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    // --- storing --------------------------------------------------------------------------------

    private void changed(UUID key, boolean atOnce) {
        if (store == null || key == null) {
            return;
        }
        dirty.add(key);
        if (atOnce || System.currentTimeMillis() - lastFlush.get() >= FLUSH_MILLIS) {
            flush();
        }
    }

    /** Writes every run whose rows changed since the last write. */
    public void flush() {
        if (store == null) {
            return;
        }
        lastFlush.set(System.currentTimeMillis());
        for (UUID key : new ArrayList<>(dirty)) {
            dirty.remove(key);
            try {
                RunUsageRecord copy;
                synchronized (lock) {
                    Book book = books.get(key);
                    if (book == null) {
                        continue;
                    }
                    copy = copyOf(key, book);
                }
                store.recordUsage(key, copy);
            } catch (RuntimeException e) {
                log.warn("The model usage kept under {} could not be saved: {}", key,
                    e.toString());
            }
        }
    }

    /** Writes what is not written yet and stops the background writing. */
    public synchronized void close() {
        if (flusher != null) {
            flusher.shutdownNow();
            flusher = null;
        }
        flush();
    }

    // --- reading --------------------------------------------------------------------------------

    /** What {@code runId} has cost so far, as a copy; empty lists for a run never seen. */
    public RunUsageRecord usageOf(UUID runId) {
        synchronized (lock) {
            return copyOf(runId, bookAt(runId, false));
        }
    }

    /**
     * Calls and lookups made for no run while no project was open, since they were first
     * counted.
     */
    public RunUsageRecord outsideRuns() {
        return outsideRunsOf(null);
    }

    /** Calls and lookups made for no run while {@code projectId} was the open project. */
    public RunUsageRecord outsideRunsOf(UUID projectId) {
        synchronized (lock) {
            return copyOf(null, bookAt(projectId == null ? NO_PROJECT : projectId, true));
        }
    }

    private static RunUsageRecord copyOf(UUID runId, Book book) {
        List<ModelUsageRecord> models = new ArrayList<>();
        book.models.values().forEach(row -> models.add(row.copy()));
        List<LookupUsageRecord> lookups = new ArrayList<>();
        book.lookups.values().forEach(row -> lookups.add(row.copy()));
        return new RunUsageRecord(book.outsideRuns ? null : runId, book.storyId, book.projectId,
            models, lookups, book.calls.stored(), book.asked.stored(), book.outsideRuns);
    }
}
