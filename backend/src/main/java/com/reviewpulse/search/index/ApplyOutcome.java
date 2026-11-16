package com.reviewpulse.search.index;

/** What applying one mutation did to the index. */
public enum ApplyOutcome {
    /** First version of a review became searchable. */
    INSERTED,
    /** Title/body/product changed: old postings retired, new postings added. */
    CONTENT_UPDATED,
    /** Only rating/helpfulness/verified/timestamp changed: no postings touched. */
    METADATA_UPDATED,
    /** A live review was removed from search. */
    DELETED,
    /** A delete arrived for a review the index never saw; remembered so older events stay ignored. */
    TOMBSTONE_RECORDED,
    /** The index already reflects this version or a newer one (duplicate or out-of-order event). */
    STALE_IGNORED;

    public boolean changedIndex() {
        return this != STALE_IGNORED;
    }
}
