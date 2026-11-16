package com.reviewpulse.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.ingestion.ReplayService;

/** Replay simulator controls. Under /api/admin, so protected by {@link AdminTokenFilter}. */
@RestController
@RequestMapping("/api/admin/replay")
public class ReplayController {

    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @GetMapping("/files")
    public ResponseEntity<ApiResponse<List<String>>> files() {
        return ResponseEntity.ok(ApiResponse.ok(replay.availableFiles()));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ReplayService.Status>> status() {
        return ResponseEntity.ok(ApiResponse.ok(replay.status()));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<ReplayService.Status>> start(@RequestBody ReplayService.StartRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(replay.start(request)));
    }

    @PostMapping("/stop")
    public ResponseEntity<ApiResponse<ReplayService.Status>> stop() {
        return ResponseEntity.ok(ApiResponse.ok(replay.stop()));
    }

    @PostMapping("/resume")
    public ResponseEntity<ApiResponse<ReplayService.Status>> resume() {
        return ResponseEntity.ok(ApiResponse.ok(replay.resume()));
    }

    @PutMapping("/rate")
    public ResponseEntity<ApiResponse<ReplayService.Status>> rate(@RequestParam Double eventsPerSecond) {
        return ResponseEntity.ok(ApiResponse.ok(replay.setRate(eventsPerSecond)));
    }
}
