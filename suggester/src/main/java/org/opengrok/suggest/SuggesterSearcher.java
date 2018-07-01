/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.suggest;

import net.openhft.chronicle.map.ChronicleMap;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.util.BytesRef;
import org.opengrok.suggest.query.data.BitIntsHolder;
import org.opengrok.suggest.query.data.IntsHolder;
import org.opengrok.suggest.query.PhraseScorer;
import org.opengrok.suggest.query.SuggesterQuery;
import org.opengrok.suggest.query.customized.CustomPhraseQuery;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class SuggesterSearcher extends IndexSearcher {

    public static final int TERM_ALREADY_SEARCHED_MULTIPLIER = 1000;

    private static final Logger logger = Logger.getLogger(SuggesterSearcher.class.getName());

    private final int resultSize;

    SuggesterSearcher(final IndexReader reader, final int resultSize) {
        super(reader);
        this.resultSize = resultSize;
    }

    public List<LookupResultItem> search(
            final Query query,
            final String suggester,
            final SuggesterQuery suggesterQuery,
            final ChronicleMap<String, Integer> searchCountMap
    ) {
        List<LookupResultItem> results = new LinkedList<>();

        Query rewrittenQuery = null;

        try {
            if (query != null) {
                rewrittenQuery = query.rewrite(getIndexReader());
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not rewrite query", e);
            return results;
        }

        for (LeafReaderContext context : this.leafContexts) {
            try {
                results.addAll(search(rewrittenQuery, context, suggester, suggesterQuery, searchCountMap));
            } catch (IOException e) {
                logger.log(Level.WARNING, "Cannot perform suggester search", e);
            }
        }

        if (results.size() > resultSize) {
            return SuggesterUtils.combineResults(results, resultSize);
        }

        return results;
    }

    public List<LookupResultItem> search(
            final Query query,
            final LeafReaderContext leafReaderContext,
            final String suggester,
            final SuggesterQuery suggesterQuery,
            final ChronicleMap<String, Integer> map
    ) throws IOException {

        boolean needsDocumentIds = query != null && !(query instanceof MatchAllDocsQuery);

        ComplexQueryData complexQueryData = null;
        if (needsDocumentIds) {
            complexQueryData = getComplexQueryData(query, leafReaderContext);
        }

        Terms terms = leafReaderContext.reader().terms(suggesterQuery.getField());

        TermsEnum termsEnum = suggesterQuery.getTermsEnumForSuggestions(terms);

        LookupPriorityQueue queue = new LookupPriorityQueue(resultSize);

        BytesRef term = termsEnum.next();

        boolean needPositionsAndFrequencies = needPositionsAndFrequencies(query);

        PostingsEnum postingsEnum = null;
        while (term != null) {
            if (needPositionsAndFrequencies) {
                postingsEnum = termsEnum.postings(postingsEnum, PostingsEnum.POSITIONS | PostingsEnum.FREQS);
            } else {
                postingsEnum = termsEnum.postings(postingsEnum, PostingsEnum.NONE);
            }

            int score;
            if (!needsDocumentIds) {
                score = termsEnum.docFreq();
            } else if (needPositionsAndFrequencies) {
                score = getPhraseScore(complexQueryData, leafReaderContext.docBase, postingsEnum);
            } else {
                score = getDocumentFrequency(complexQueryData.documentIds, leafReaderContext.docBase, postingsEnum);
            }

            if (score > 0) {
                int add = map.getOrDefault(term.utf8ToString(), 0);
                score += add * TERM_ALREADY_SEARCHED_MULTIPLIER;

                queue.insertWithOverflow(new LookupResultItem(term.utf8ToString(), suggester, score));
            }

            term = termsEnum.next();
        }

        return queue.getResult();
    }

    private ComplexQueryData getComplexQueryData(final Query query, final LeafReaderContext leafReaderContext) {
        ComplexQueryData data = new ComplexQueryData();
        if (query == null || query instanceof SuggesterQuery) {
            data.documentIds = new BitIntsHolder(0);
            return data;
        }

        BitIntsHolder documentIds = new BitIntsHolder();
        try {
            search(query, new Collector() {
                @Override
                public LeafCollector getLeafCollector(final LeafReaderContext context) {
                    return new LeafCollector() {

                        final int docBase = context.docBase;

                        @Override
                        public void setScorer(final Scorer scorer) {
                            if (leafReaderContext == context) {
                                if (scorer instanceof PhraseScorer) {
                                    data.scorer = (PhraseScorer) scorer;
                                } else {
                                    try {
                                        // it is mentioned in the documentation that #getChildren should not be called
                                        // in #setScorer but no better way was found
                                        for (Scorer.ChildScorer childScorer : scorer.getChildren()) {
                                            if (childScorer.child instanceof PhraseScorer) {
                                                data.scorer = (PhraseScorer) childScorer.child;
                                            }
                                        }
                                    } catch (Exception e) {
                                        // ignore
                                    }
                                }
                            }
                        }

                        @Override
                        public void collect(int doc) {
                            if (leafReaderContext == context) {
                                documentIds.set(docBase + doc);
                            }
                        }
                    };
                }

                @Override
                public boolean needsScores() {
                    return false;
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not get document ids for " + query, e);
        }

        data.documentIds = documentIds;
        return data;
    }

    private int getPhraseScore(final ComplexQueryData data, final int docBase, final PostingsEnum postingsEnum)
            throws IOException {

        int weight = 0;
        while (postingsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            int docId = postingsEnum.docID();
            if (data.documentIds.has(docBase + docId)) {
                IntsHolder positions = data.scorer.getPositions(docBase + docId);
                if (positions == null) {
                    continue;
                }

                int freq = postingsEnum.freq();
                for (int i = 0; i < freq; i++) {
                    int pos = postingsEnum.nextPosition();

                    if (positions.has(pos)) {
                        weight++;
                    }
                }
            }
        }

        return weight;
    }

    private int getDocumentFrequency(final IntsHolder documentIds, final int docBase, final PostingsEnum postingsEnum)
            throws IOException {

        int weight = 0;
        while (postingsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            if (documentIds.has(docBase + postingsEnum.docID())) {
                weight++;
            }
        }
        return weight;
    }

    private boolean needPositionsAndFrequencies(final Query query) {
        if (query instanceof CustomPhraseQuery) {
            return true;
        }

        if (query instanceof BooleanQuery) {
            for (BooleanClause bc : ((BooleanQuery) query).clauses()) {
                if (needPositionsAndFrequencies(bc.getQuery())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static class ComplexQueryData {

        private IntsHolder documentIds;

        private PhraseScorer scorer;

    }

}
