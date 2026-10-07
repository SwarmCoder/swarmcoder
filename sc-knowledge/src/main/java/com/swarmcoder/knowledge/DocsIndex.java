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

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class DocsIndex {

    private static final Logger log = LoggerFactory.getLogger(DocsIndex.class);

    private final Directory directory;
    private final StandardAnalyzer analyzer;

    public DocsIndex(Path indexPath) throws IOException {
        this.directory = new MMapDirectory(indexPath);
        this.analyzer = new StandardAnalyzer();
    }

    public void indexDocs(String libraryId, String content) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            Document doc = new Document();
            doc.add(new StringField("libraryId", libraryId, Field.Store.YES));
            doc.add(new TextField("content", content, Field.Store.YES));
            writer.addDocument(doc);
        } catch (IOException e) {
            log.warn("Failed to index docs for library '{}'", libraryId, e);
        }
    }

    public Optional<String> lookupApi(String libraryId, String symbol) {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new QueryParser("content", analyzer);
            Query parsed = parser.parse(symbol);
            // The library filter, which was a TODO and a real defect: without it a question about
            // one library could be answered out of another library's documentation, confidently
            // and with no sign that it had happened. A blank id still means "any library".
            Query query = libraryId == null || libraryId.isBlank() ? parsed
                : new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("libraryId", libraryId)), BooleanClause.Occur.FILTER)
                    .add(parsed, BooleanClause.Occur.MUST)
                    .build();
            TopDocs results = searcher.search(query, 1);
            if (results.scoreDocs.length > 0) {
                ScoreDoc scoreDoc = results.scoreDocs[0];
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                return Optional.ofNullable(doc.get("content"));
            }
        } catch (IndexNotFoundException e) {
            // Nothing has ever been indexed here — the normal state whenever library docs are
            // switched off (no CONTEXT7_API_KEY, see EnvironmentChecks), and every lookup a worker
            // makes hits this until something is indexed. "Not found" is the correct answer, not a
            // fault, so unlike the branch below this is not logged with a stack trace: it used to
            // print the full Koog/reflection call chain on every single worker lookup, drowning out
            // whatever else was in the log (see DEVELOPER_CORRECTIONS.md).
            log.debug("Docs lookup for '{}' in library '{}': nothing indexed yet", symbol, libraryId);
        } catch (Exception e) {
            log.warn("Docs lookup failed for '{}' in library '{}'", symbol, libraryId, e);
        }
        return Optional.empty();
    }
}
