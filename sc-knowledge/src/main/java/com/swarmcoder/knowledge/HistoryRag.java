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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.TraceEvent;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.IndexNotFoundException;

/**
 * The Context Ledger's searchable history (spec §12.3): completed agent sessions are indexed
 * so any agent can ask "have we seen this error before?" via {@code search_history}. v1 is
 * keyword search over the transcript text (Lucene); embeddings (bge-small ONNX) are the M4
 * tail. Indexing one session on completion is idempotent — re-indexing replaces the entry.
 */
public class HistoryRag {

    /** One search hit: enough to orient, with the matching snippet. */
    public record Hit(String sessionId, String taskId, String outcome, String snippet, float score) {}

    private static final Logger log = LoggerFactory.getLogger(HistoryRag.class);
    private static final int SNIPPET_CHARS = 600;

    private final Directory directory;
    private final StandardAnalyzer analyzer = new StandardAnalyzer();

    public HistoryRag(Path indexPath) throws IOException {
        this.directory = new MMapDirectory(indexPath);
    }

    /** Indexes (or re-indexes) one completed session's transcript. */
    public synchronized void index(AgentSessionRecord session) {
        String text = transcript(session);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            writer.deleteDocuments(new Term("sessionId", session.id().toString()));
            Document doc = new Document();
            doc.add(new StringField("sessionId", session.id().toString(), Field.Store.YES));
            doc.add(new StringField("taskId", session.taskId() == null ? "" : session.taskId().toString(), Field.Store.YES));
            doc.add(new StringField("runId", session.runId() == null ? "" : session.runId().toString(), Field.Store.YES));
            doc.add(new StringField("outcome", session.outcome() == null ? "" : session.outcome(), Field.Store.YES));
            doc.add(new TextField("text", text, Field.Store.NO));
            doc.add(new StoredField("snippet", text.length() > SNIPPET_CHARS ? text.substring(0, SNIPPET_CHARS) : text));
            writer.addDocument(doc);
        } catch (IOException e) {
            log.warn("History index failed for session {}: {}", session.id(), e.getMessage());
        }
    }

    /** Top-k sessions whose transcript matches the free-text query. Empty on no index / parse error. */
    public List<Hit> search(String query, int k) {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return hits;
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query parsed = new QueryParser("text", analyzer).parse(QueryParser.escape(query));
            for (ScoreDoc scoreDoc : searcher.search(parsed, Math.max(1, k)).scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new Hit(doc.get("sessionId"), doc.get("taskId"), doc.get("outcome"),
                    doc.get("snippet"), scoreDoc.score));
            }
        } catch (IndexNotFoundException e) {
            return hits; // nothing indexed yet
        } catch (Exception e) {
            log.warn("History search failed for '{}': {}", query, e.getMessage());
        }
        return hits;
    }

    private static String transcript(AgentSessionRecord session) {
        StringBuilder sb = new StringBuilder();
        sb.append(session.role()).append(" on task ").append(session.taskId())
            .append(" (").append(session.outcome()).append(")\n");
        if (session.events() != null) {
            for (TraceEvent event : session.events()) {
                sb.append(event.kind());
                if (event.label() != null && !event.label().isBlank()) {
                    sb.append(' ').append(event.label());
                }
                if (event.payload() != null && !event.payload().isBlank()) {
                    sb.append(": ").append(event.payload());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
