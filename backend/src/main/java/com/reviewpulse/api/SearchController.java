package com.reviewpulse.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.search.SearchRequest;
import com.reviewpulse.search.SearchResponse;
import com.reviewpulse.search.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "Search reviews with the custom inverted index",
            description = "BM25F relevance over title and body, with filters, sorts and keyset pagination.")
    public ResponseEntity<ApiResponse<SearchResponse>> search(
            @Parameter(description = "Keywords (1-256 chars)") @RequestParam(required = false) String q,
            @Parameter(description = "AND (default) or OR") @RequestParam(required = false) String operator,
            @RequestParam(required = false) Integer ratingMin,
            @RequestParam(required = false) Integer ratingMax,
            @RequestParam(required = false) String parentAsin,
            @RequestParam(required = false) Boolean verifiedPurchase,
            @Parameter(description = "relevance (default), recency or helpfulness") @RequestParam(required = false) String sort,
            @Parameter(description = "1-50, default 20") @RequestParam(required = false) Integer pageSize,
            @Parameter(description = "nextCursor from the previous page") @RequestParam(required = false) String cursor) {
        SearchService.Outcome outcome = search.search(new SearchRequest(q, operator, ratingMin, ratingMax, parentAsin,
                verifiedPurchase, sort, pageSize, cursor));
        return ResponseEntity.ok()
                .header("X-Index-State", String.valueOf(outcome.meta().get("indexState")))
                .body(ApiResponse.ok(outcome.response(), outcome.meta()));
    }
}
