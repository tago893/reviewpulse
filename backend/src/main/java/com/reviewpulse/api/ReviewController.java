package com.reviewpulse.api;

import java.util.OptionalLong;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.reviews.ReviewView;
import com.reviewpulse.search.index.SearchIndex;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewRepository reviews;
    private final SearchIndex index;

    public ReviewController(ReviewRepository reviews, SearchIndex index) {
        this.reviews = reviews;
        this.index = index;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReviewView>> get(@PathVariable String id) {
        long reviewId = ReviewIds.parse(id);
        Review review = reviews.findById(reviewId)
                .filter(r -> !r.deleted())
                .orElseThrow(() -> new NotFoundException("review not found"));
        return ResponseEntity.ok(ApiResponse.ok(view(review)));
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
