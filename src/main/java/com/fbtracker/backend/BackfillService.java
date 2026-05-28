package com.fbtracker.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class BackfillService {
    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    // Fitbit personal app limit is 150 requests/hour. A day's sync makes ~6 requests
    // (3 activity + heartrate + oxygen + sleep), so 25s between requests keeps us
    // under the limit. ~2.5 min per day, ~15 hr per year of data.
    private static final long REQUEST_PACING_MS = 25_000;

    private final BackfillJobRepository repo;
    private final SyncService syncService;

    public BackfillService(BackfillJobRepository repo, SyncService syncService) {
        this.repo = repo;
        this.syncService = syncService;
    }

    public BackfillJob start(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end date must not be before start date");
        }
        BackfillJob job = new BackfillJob(start, end);
        return repo.save(job);
    }

    @Async
    public void run(UUID jobId) {
        BackfillJob job = repo.findById(jobId)
            .orElseThrow(() -> new IllegalStateException("Backfill job not found: " + jobId));
        log.info("Starting backfill job {} from {} to {}", jobId, job.getCurrentDate(), job.getEndDate());
        job.setStatus(BackfillJob.Status.RUNNING);
        repo.save(job);

        try {
            LocalDate date = job.getCurrentDate();
            while (!date.isAfter(job.getEndDate())) {
                syncDay(date);
                date = date.plusDays(1);
                job.setCurrentDate(date);
                repo.save(job);
            }
            job.setStatus(BackfillJob.Status.COMPLETED);
            job.setCompletedAt(Instant.now());
            log.info("Backfill job {} completed", jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.setStatus(BackfillJob.Status.FAILED);
            job.setErrorMessage("interrupted");
            log.warn("Backfill job {} interrupted at {}", jobId, job.getCurrentDate());
        } catch (Exception e) {
            job.setStatus(BackfillJob.Status.FAILED);
            job.setErrorMessage(e.getMessage());
            log.error("Backfill job {} failed at {}: {}", jobId, job.getCurrentDate(), e.getMessage());
        } finally {
            repo.save(job);
        }
    }

    private void syncDay(LocalDate date) throws InterruptedException {
        log.info("Backfilling {}", date);
        syncService.syncActivitiesForDate(date);
        Thread.sleep(REQUEST_PACING_MS * 3);
        syncService.syncHeartrateForDate(date);
        Thread.sleep(REQUEST_PACING_MS);
        syncService.syncOxygenForDate(date);
        Thread.sleep(REQUEST_PACING_MS);
        syncService.syncSleepForDate(date);
        Thread.sleep(REQUEST_PACING_MS);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedJobs() {
        List<BackfillJob> runningJobs = repo.findByStatus(BackfillJob.Status.RUNNING);
        for (BackfillJob job : runningJobs) {
            log.info("Resuming interrupted backfill job {} from {}", job.getId(), job.getCurrentDate());
            run(job.getId());
        }
    }
}
