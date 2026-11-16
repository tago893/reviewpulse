package com.reviewpulse.search.index;

/**
 * Point-in-time size statistics of an index.
 *
 * @param liveDocs         searchable documents
 * @param docSlots         docIds allocated (live + dead awaiting compaction)
 * @param terms            vocabulary size
 * @param postings         posting entries, including entries of dead docs
 * @param products         distinct parent ASINs
 * @param trackedReviews   reviews with a known version (live + tombstones)
 * @param approximateBytes estimated heap used by index structures
 */
public record IndexStats(int liveDocs, int docSlots, int terms, long postings, int products,
                         int trackedReviews, long approximateBytes) {

    public int deadDocs() {
        return docSlots - liveDocs;
    }

    public double deadRatio() {
        return docSlots == 0 ? 0.0 : (double) deadDocs() / docSlots;
    }
}
