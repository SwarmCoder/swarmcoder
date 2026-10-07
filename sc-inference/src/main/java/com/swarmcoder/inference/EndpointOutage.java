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
package com.swarmcoder.inference;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;

/**
 * "The model was not there" — the one place that is distinguished from "the model said no".
 *
 * <p>The engine has to treat those two as different kinds of event, and until now it did not. An
 * unreachable Spark produced exactly what a bad answer produced: the architect's design call
 * returned null and the run continued on a minimal design, the planner returned null and the run
 * continued on the single-task fallback, every worker died and the run spent its one repair round
 * on candidates that had never reached a model. A day's worth of outage was therefore indistinguishable
 * from a day's worth of genuine failure, and it cost budget, repair rounds and operator attention
 * that a refusal is entitled to and an outage is not.
 *
 * <p>So: an outage is not a verdict about the work. Nothing may be concluded from it, no budget may
 * be spent on it, and the correct response is to wait and try the same thing again — which is what
 * the workflow does when it catches this (see {@code OutagePause}).
 *
 * <p><b>The classifier defaults to "refusal".</b> {@link #isOutage} recognises transport failures by
 * type; anything it does not recognise is treated as a genuine failure, i.e. exactly the behaviour
 * that existed before this class. A missed outage therefore degrades to the old handling rather than
 * to a run that silently retries forever, which is the only safe direction for a guess to fall.
 */
public class EndpointOutage extends RuntimeException {

    /** How far down a cause chain to look. Generous; see {@link #isOutage}. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final String endpoint;

    public EndpointOutage(String endpoint, String detail, Throwable cause) {
        super((endpoint == null ? "model endpoint" : endpoint) + " did not answer: " + detail, cause);
        this.endpoint = endpoint;
    }

    /** The endpoint that did not answer, for the sentence the operator reads and the health link. */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Whether this failure means the endpoint was not there, rather than that the model answered
     * badly.
     *
     * <p>Walks the cause chain: the agent frameworks wrap transport exceptions several layers deep,
     * and the layer we see says nothing while the cause says everything.
     */
    public static boolean isOutage(Throwable failure) {
        // Depth-bounded rather than cycle-detected: cause chains CAN be circular (A caused by B,
        // B later given A as its cause — the JDK only forbids self-causation), and this is consulted
        // on every failure, so hanging here would take the whole engine with it. No real chain is
        // anywhere near this deep.
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isOutageType(t)) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /**
     * The one request that timed out, and what it looked like — the context
     * {@link #isOutage(Throwable, Attempt)} needs to tell a slow request from an absent server.
     *
     * @param endpoint      which box the request went to
     * @param sentAt        when the request left. Evidence only counts if it is at least this
     *                      recent: a success from before the request was sent says nothing about
     *                      whether the server was still there while the request sat unanswered.
     * @param promptTokens  estimated size of what was sent, or 0 when unknown
     * @param budgetTokens  the working context this model is allowed per session, or 0 when unknown
     */
    public record Attempt(String endpoint, java.time.Instant sentAt, int promptTokens, int budgetTokens) {

        /** True when we sent more than this model's own working-context budget allows. */
        public boolean oversized() {
            return budgetTokens > 0 && promptTokens > budgetTokens;
        }

        /** True when the conversation sent was known and at most half the working context. */
        public boolean smallConversation() {
            return promptTokens > 0 && budgetTokens > 0 && promptTokens <= budgetTokens / 2;
        }
    }

    /**
     * Whether this failure means the endpoint was not there, judged with what else was happening on
     * that endpoint at the time.
     *
     * <p><b>What this changes and what it deliberately does not.</b> Only ONE family of failure is
     * re-judged: a request timeout, meaning the server accepted the request and had not finished
     * answering when the client gave up ({@link #isRequestTimeout}). Everything else — connection
     * refused, unknown host, a connect timeout, a connection closed mid-exchange — is left exactly
     * as {@link #isOutage(Throwable)} decides it, because those all say the box was not reachable
     * and no amount of surrounding evidence changes that. The model-server restart that produced 43
     * simultaneous "connection closed by peer" failures is untouched by this method.
     *
     * <p>A request timeout stops being an outage when either of two things is true:
     *
     * <ol>
     *   <li><b>Something else got a complete answer out of that endpoint while this request was in
     *       flight.</b> A finished completion is proof the inference engine was serving; a request
     *       that sat unanswered through it was too big or too slow, not abandoned. (Deliberately not
     *       a health probe: a live HTTP front end in front of a wedged engine would answer one, and
     *       believing it would turn a real outage into workers blamed for it.)
     *   <li><b>We sent more than this model's working context allows.</b> Then we oversized the
     *       request ourselves and no server is at fault, whatever else was going on.
     * </ol>
     *
     * <p>With no evidence either way the answer is the same as it has always been: outage. A missed
     * slow request costs a pause that resolves itself; a missed outage costs a run's repair round on
     * candidates that never reached a model, which is the more expensive mistake.
     */
    public static boolean isOutage(Throwable failure, Attempt attempt) {
        if (!isOutage(failure)) {
            return false;
        }
        if (attempt == null || !isRequestTimeout(failure)) {
            return true;
        }
        if (attempt.oversized()) {
            return false;
        }
        if (EndpointActivity.succeededSince(attempt.endpoint(), attempt.sentAt())) {
            return false;
        }
        // Nobody else was answered meanwhile - which is also what it looks like when nobody else
        // was asking (live run 74, 2026-10-03: the last two workers of a repair round timed out
        // three seconds apart; the first found the second's silence "no success since" and the
        // run paused for a server outage that was not one). So the server is asked, now: one
        // that answers is not out, and this was a request that took too long.
        return !answersNow.test(attempt.endpoint());
    }

    /** Whether the endpoint answers a cheap question right now; replaceable in tests. */
    private static volatile java.util.function.Predicate<String> answersNow =
        EndpointOutage::probedUp;

    private static final EndpointReachability REACHABILITY = new EndpointReachability();

    private static boolean probedUp(String endpoint) {
        try {
            return REACHABILITY.probe(endpoint).status() == EndpointReachability.Status.UP;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** For tests: how "does it answer now" is established; null restores the real probe. */
    static void answersNowIs(java.util.function.Predicate<String> probe) {
        answersNow = probe == null ? EndpointOutage::probedUp : probe;
    }

    /**
     * Whether the server ACCEPTED the request and then failed to finish answering it in time.
     *
     * <p>Narrow on purpose. Only the two types that can mean nothing else are matched: ktor's
     * request-timeout plugin, which is what kills a worker whose prompt has grown too large to
     * prefill, and {@link HttpTimeoutException} from {@code java.net.http} — but explicitly NOT its
     * subclass {@code HttpConnectTimeoutException}, which fires before the request is sent and is a
     * plain unreachable box. {@link java.net.SocketTimeoutException} is also left out: the JDK
     * throws it for a connect timeout as well as a read timeout, so it cannot carry this meaning on
     * its own. Anything not matched here keeps today's classification exactly.
     */
    public static boolean isRequestTimeout(Throwable failure) {
        Throwable t = failure;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (t instanceof java.net.http.HttpConnectTimeoutException) {
                return false;
            }
            if (t instanceof HttpTimeoutException
                || "HttpRequestTimeoutException".equals(t.getClass().getSimpleName())) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /**
     * What the operator is told when a request timed out and the endpoint was demonstrably alive.
     * Says what happened, why it is not the server's fault, and what changes it.
     */
    public static String slowRequestSentence(Attempt attempt) {
        return slowRequestSentence(attempt, 0, 0);
    }

    /**
     * What is known about a request that was not answered in time, and what to do about it.
     *
     * <p>Live run 74, 2026-10-03: the old sentence blamed the size of the conversation ("lower
     * this model's working context") for requests of 9,000 to 17,000 tokens against a working
     * context of 262,144. They were small. What did not fit in the time allowed was the ANSWER:
     * the call is not streamed, the turn may ask for tens of thousands of output tokens, and on
     * a loaded server the model writes about eleven a second. So the sentence now says which of
     * the two it was, from the figures.
     *
     * @param timeoutSeconds   how long the client waits, 0 when not known
     * @param turnOutputTokens the output tokens the turn was allowed, 0 when not known
     */
    public static String slowRequestSentence(Attempt attempt, int timeoutSeconds,
                                             int turnOutputTokens) {
        boolean small = attempt != null && attempt.smallConversation();
        if (small) {
            return "this request was not answered in the "
                + (timeoutSeconds > 0 ? timeoutSeconds + " seconds" : "time") + " the client waits"
                + ", and the server is up. The conversation it sent was small - about "
                + attempt.promptTokens() + " tokens against a working context of "
                + attempt.budgetTokens() + " - so its size is not the cause: the answer was still "
                + "being written. The call is not streamed, so an unfinished answer is lost whole"
                + (turnOutputTokens > 0 ? "; the turn was allowed " + turnOutputTokens
                    + " output tokens" : "")
                + ". Nothing is wrong with the endpoint. Raise -D" + RequestTimeouts.PROPERTY
                + ", or state the model's loadedTokensPerSecond in its shape so a turn is not "
                + "allowed more output than the server can write in that time.";
        }
        StringBuilder sb = new StringBuilder("this worker's own request took longer than the client "
            + "would wait, while the model server kept answering other requests normally");
        if (attempt != null && attempt.promptTokens() > 0) {
            sb.append(" — the conversation it sent was about ").append(attempt.promptTokens())
                .append(" tokens");
            if (attempt.budgetTokens() > 0) {
                sb.append(" against a working context of ").append(attempt.budgetTokens());
            }
        }
        sb.append(". Nothing is wrong with the endpoint: lower this model's working context so the "
            + "conversation is compacted sooner, or lower the turn allowance.");
        return sb.toString();
    }

    /**
     * Wraps a failure as an outage when it is one, otherwise returns null.
     *
     * <p>The endpoint has to be passed in: by the time an exception surfaces, the URL it was aimed
     * at is no longer anywhere in it, and "your model server is not answering" without saying WHICH
     * one is not something an operator can act on.
     */
    public static EndpointOutage from(String endpoint, Throwable failure) {
        if (!isOutage(failure)) {
            return null;
        }
        if (failure instanceof EndpointOutage outage) {
            return outage;
        }
        Throwable root = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && !isOutageType(root); depth++) {
            Throwable next = root.getCause();
            if (next == null || next == root) {
                break;
            }
            root = next;
        }
        return new EndpointOutage(endpoint,
            root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : " (" + root.getMessage() + ")"),
            failure);
    }

    private static boolean isOutageType(Throwable t) {
        return t instanceof EndpointOutage
            || t instanceof ConnectException            // refused — nothing listening
            || t instanceof NoRouteToHostException
            || t instanceof PortUnreachableException
            || t instanceof UnknownHostException
            || t instanceof UnresolvedAddressException
            || t instanceof SocketTimeoutException
            || t instanceof HttpTimeoutException       // java.net.http connect / request timeout
            || t instanceof InterruptedIOException     // Apache httpclient5 connect/socket timeouts
            || t instanceof ClosedChannelException
            || t instanceof EOFException               // connection dropped mid-response
            || isOutageTypeByName(t);
    }

    /**
     * Transport failures from engines whose types we deliberately do not depend on.
     *
     * <p>Koog talks to the model through ktor, which in turn talks over Apache httpclient5, and
     * sc-inference must not take a dependency on either to name a handful of exception classes.
     * Matching the simple name is the price of that boundary; it is best-effort by construction,
     * and a miss falls back to "refusal", which is the safe direction.
     */
    private static boolean isOutageTypeByName(Throwable t) {
        return switch (t.getClass().getSimpleName()) {
            case "HttpRequestTimeoutException",     // ktor client timeout plugin
                 "ConnectTimeoutException",         // ktor / Apache connect timeout
                 "SocketTimeoutException",
                 "ServerResponseException",         // ktor's 5xx wrapper

                 // Apache httpcore5, the transport under Koog's ktor-apache5 engine. All three
                 // extend plain java.io.IOException — NOT IOException subtypes this class already
                 // names — so nothing above catches them, and every one of them means the socket
                 // died with no answer on it. None can carry a model refusal or a bad request:
                 // a refusal is an HTTP response, and by the time one of these is thrown there is
                 // no response.
                 "ConnectionClosedException",       // the server closed the connection mid-exchange.
                                                    // This is what a model-server restart looks
                                                    // like: 43 in-flight workers, all of them
                                                    // "Connection closed by peer", all of them
                                                    // previously recorded as genuine failures.
                 "RequestNotExecutedException",     // subclass of the above; the connection closed
                                                    // BEFORE the request was sent, so the endpoint
                                                    // provably never saw it. Named separately
                                                    // because this matcher compares exact simple
                                                    // names and does not follow a hierarchy.
                 "NoHttpResponseException"          // the server accepted the connection and then
                                                    // dropped it without a status line — a box on
                                                    // its way down, or a half-closed pooled socket
                -> true;
            // Deliberately NOT listed: org.apache.hc.core5.http.ConnectionRequestTimeoutException
            // (pool lease timeout) needs no entry — it extends java.io.InterruptedIOException, which
            // isOutageType already matches. Also not listed: HttpStreamResetException and
            // StreamClosedException, where an HTTP/2 RST_STREAM can be the server rejecting THIS
            // request while it serves everything else — that is a refusal, not an absent endpoint.
            default -> false;
        };
    }
}
