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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many requests one model server is sent at the same moment, whoever is asking.
 *
 * <p><b>Why this exists (2026-10-02).</b> A server serves a fixed number of requests at once -
 * four, on the box this was written for. Workers were admitted against that number by
 * {@link InferenceScheduler}, but a worker is not the only thing that talks to its server: the
 * judge, the expert a worker asks, the test author and the reviewer all send requests of their
 * own, and none of them was counted. With four workers going, a judge call was a fifth request
 * to a server that serves four.
 *
 * <p><b>One place is one request, not one session.</b> A place is taken when a request is sent
 * and given back when its answer has arrived. Nothing holds a place while it waits for something
 * else - a worker waiting for the expert has no request in flight and holds none - so two callers
 * can never each hold what the other is waiting for. The places are handed out in the order they
 * were asked for.
 *
 * <p><b>Only a registered server is counted.</b> A role that is configured on an endpoint of its
 * own (a cloud model) is not registered here, takes no place and never waits. That is also what
 * every test and embedded use gets.
 */
public final class ServerPlaces {

    private static final Logger log = LoggerFactory.getLogger(ServerPlaces.class);

    private record Server(int atOnce, Semaphore places) { }

    private static final Map<String, Server> SERVERS = new ConcurrentHashMap<>();

    private ServerPlaces() { }

    /** A place on a server, held for one request. Giving it back twice is harmless. */
    public interface Place extends AutoCloseable {
        @Override
        void close();
    }

    private static final Place NONE = () -> { };

    /**
     * Counts the requests sent to the server at {@code baseUrl}: at most {@code atOnce} are in
     * flight together. Registering the same server again with the same number changes nothing;
     * with a different number the new one applies to requests sent from then on.
     */
    public static void register(String baseUrl, int atOnce) {
        if (baseUrl == null || baseUrl.isBlank() || atOnce <= 0) {
            return;
        }
        String root = ServerCapabilities.root(baseUrl);
        Server known = SERVERS.get(root);
        if (known != null && known.atOnce() == atOnce) {
            return;
        }
        SERVERS.put(root, new Server(atOnce, new Semaphore(atOnce, true)));
        log.info("The model server at {} is sent at most {} request(s) at once, counting every "
            + "role that uses it: workers, judge, expert, test author and reviewer alike.", root,
            atOnce);
    }

    /** Stops counting a server. For tests. */
    public static void forget(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            SERVERS.remove(ServerCapabilities.root(baseUrl));
        }
    }

    /** How many requests the server at {@code baseUrl} is sent at once; 0 when it is not counted. */
    public static int atOnce(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return 0;
        }
        Server server = SERVERS.get(ServerCapabilities.root(baseUrl));
        return server == null ? 0 : server.atOnce();
    }

    /**
     * Takes a place for one request to the server at {@code baseUrl}, waiting for one when the
     * server is full. A server nobody registered answers at once with a place that is nothing.
     */
    public static Place enter(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return NONE;
        }
        Server server = SERVERS.get(ServerCapabilities.root(baseUrl));
        if (server == null) {
            return NONE;
        }
        Semaphore places = server.places();
        places.acquireUninterruptibly();
        AtomicBoolean given = new AtomicBoolean();
        return () -> {
            if (given.compareAndSet(false, true)) {
                places.release();
            }
        };
    }
}
