package com.fbtracker.backend;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final DiscordNotificationService discordService;
    private final DailySummaryService dailySummaryService;
    private final BackfillService backfillService;
    private final BackfillJobRepository backfillRepo;

    public AdminController(DiscordNotificationService discordService,
                           DailySummaryService dailySummaryService,
                           BackfillService backfillService,
                           BackfillJobRepository backfillRepo) {
        this.discordService = discordService;
        this.dailySummaryService = dailySummaryService;
        this.backfillService = backfillService;
        this.backfillRepo = backfillRepo;
    }

    @GetMapping("/test-discord")
    public String testDiscord(@RequestParam(defaultValue = "test from fbtracker") String message) {
        discordService.sendMessage(message);
        return "Discord notification sent: " + message;
    }

    @GetMapping("/test-summary")
    public String testSummary() {
        dailySummaryService.sendDailySummary();
        return "Daily summary triggered — check Discord";
    }

    @PostMapping("/backfill")
    public ResponseEntity<?> startBackfill(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        List<BackfillJob> running = backfillRepo.findByStatus(BackfillJob.Status.RUNNING);
        if (!running.isEmpty()) {
            return ResponseEntity.status(409).body(
                "A backfill job is already running (id=" + running.get(0).getId() + "). Wait for it to complete or fail."
            );
        }
        BackfillJob job = backfillService.start(start, end);
        backfillService.run(job.getId());
        return ResponseEntity.ok(job);
    }

    @PostMapping("/backfill-calories")
    public ResponseEntity<?> backfillCalories(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        if (end.isBefore(start)) {
            return ResponseEntity.badRequest().body("end date must not be before start date");
        }
        backfillService.runCaloriesBackfill(start, end);
        return ResponseEntity.ok("Calories-only backfill started for " + start + " to " + end);
    }

    @GetMapping("/backfill")
    public List<BackfillJob> listBackfills() {
        return backfillRepo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/backfill/{id}")
    public ResponseEntity<BackfillJob> getBackfill(@PathVariable UUID id) {
        return backfillRepo.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
