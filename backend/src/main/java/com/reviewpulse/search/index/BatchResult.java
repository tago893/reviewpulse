package com.reviewpulse.search.index;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Outcome of applying one batch: one outcome per mutation, in input order. */
public record BatchResult(List<ApplyOutcome> outcomes) {

    public BatchResult {
        outcomes = List.copyOf(outcomes);
    }

    public Map<ApplyOutcome, Integer> counts() {
        Map<ApplyOutcome, Integer> counts = new EnumMap<>(ApplyOutcome.class);
        for (ApplyOutcome o : outcomes) {
            counts.merge(o, 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public boolean changedIndex() {
        return outcomes.stream().anyMatch(ApplyOutcome::changedIndex);
    }
}
