package com.reviewpulse.api;

import java.net.URI;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.reviews.ReviewService;
import com.reviewpulse.reviews.ReviewView;
import com.reviewpulse.search.index.SearchIndex;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Review reads and writes. A successful write means "committed in PostgreSQL"; it becomes searchable
 * asynchronously. Responses say so explicitly ({@code meta.indexing = PENDING}) and GET reports
 * {@code searchable} once the index reflects the returned version.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private static final Map<String, Object> PENDING = Map.of(
            "indexing", "PENDING",
            "hint", "the change is committed; poll GET /api/reviews/{id} until searchable is true");

    private final ReviewRepository reviews;
    private final ReviewService service;
    private final SearchIndex index;

    public ReviewController(ReviewRepository reviews, ReviewService service, SearchIndex index) {
        this.reviews = reviews;
        this.service = service;
        this.index = index;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReviewView>> get(@PathVariable String id) {
        Review review = reviews.findById(ReviewIds.parse(id))
                .filter(r -> !r.deleted())
                .orElseThrow(() -> new NotFoundException("review not found"));
        return ResponseEntity.ok(ApiResponse.ok(view(review)));
    }

    @PostMapping
    @Operation(summary = "Create a review (searchable after asynchronous indexing)")
    public ResponseEntity<ApiResponse<ReviewView>> create(@RequestBody ReviewService.CreateCommand body) {
        Review review = service.create(body);
        return ResponseEntity.created(URI.create("/api/reviews/" + review.id()))
                .body(ApiResponse.ok(view(review), PENDING));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace title/body/rating; requires expectedVersion (409 on conflict)")
    public ResponseEntity<ApiResponse<ReviewView>> update(@PathVariable String id,
                                                          @RequestBody ReviewService.UpdateCommand body) {
        Review review = service.update(ReviewIds.parse(id), body);
        return ResponseEntity.ok(ApiResponse.ok(view(review), PENDING));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a review; requires expectedVersion (409 on conflict)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable String id,
                                                                   @RequestParam(required = false) Long expectedVersion) {
        Review review = service.delete(ReviewIds.parse(id), expectedVersion);
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("id", Long.toString(review.id()), "version", review.version(), "deleted", true), PENDING));
    }

    @PostMapping("/{id}/helpful")
    @Operation(summary = "Add one helpful vote (no text reindexing; metadata-only index update)")
    public ResponseEntity<ApiResponse<ReviewView>> helpful(@PathVariable String id) {
        Review review = service.markHelpful(ReviewIds.parse(id));
        return ResponseEntity.ok(ApiResponse.ok(view(review), PENDING));
    }

    private ReviewView view(Review review) {
        boolean[] live = new boolean[1];
        OptionalLong indexed = index.read(idx -> {
            live[0] = idx.liveDocId(review.id()).isPresent();
            return idx.appliedVersion(review.id());
        });
        Long indexedVersion = indexed.isPresent() ? indexed.getAsLong() : null;
        boolean searchable = live[0] && indexedVersion != null && indexedVersion == review.version();
        return ReviewView.of(review, indexedVersion, searchable);
    }
}
