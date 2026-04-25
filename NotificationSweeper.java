package com.grid07.scheduler;

import com.grid07.service.RedisGuardrailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3 — The CRON Sweeper.
 *
 * Runs every 5 minutes (simulating the 15-min production sweep).
 * For each user with pending notifications:
 *   1. Pop all pending messages from their Redis list.
 *   2. Log a summarised push notification.
 *   3. Clear the Redis list.
 *
 * Pattern used to extract userId from key: "user:{id}:pending_notifs"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSweeper {

    private static final Pattern USER_ID_PATTERN =
        Pattern.compile("user:(\\d+):pending_notifs");

    private final RedisGuardrailService redisService;

    /**
     * fixedRate = 5 min in milliseconds.
     * initialDelay = 60 s so the app has time to start up before the first sweep.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void sweepPendingNotifications() {
        log.info("[SWEEPER] Starting notification sweep...");

        Set<String> keys = redisService.getAllPendingNotifKeys();
        if (keys == null || keys.isEmpty()) {
            log.info("[SWEEPER] No pending notifications found.");
            return;
        }

        log.info("[SWEEPER] Found {} user(s) with pending notifications.", keys.size());

        for (String key : keys) {
            Long userId = extractUserId(key);
            if (userId == null) {
                log.warn("[SWEEPER] Could not parse userId from key: {}", key);
                continue;
            }

            List<String> messages = redisService.drainPendingNotifs(userId);
            if (messages.isEmpty()) continue;

            // Build summarised notification message
            String summary = buildSummary(messages);
            log.info("[SWEEPER] Summarised Push Notification for user {}: {}", userId, summary);
        }

        log.info("[SWEEPER] Sweep complete.");
    }

    /**
     * Produces:
     *   "Bot Alpha and 2 others interacted with your posts."   (if > 1 message)
     *   "Bot Alpha interacted with your posts."                (if exactly 1)
     */
    private String buildSummary(List<String> messages) {
        if (messages.isEmpty()) return "(no messages)";

        // The first message acts as the headline; remaining are collapsed into a count.
        String firstMessage = messages.get(0);
        int    others       = messages.size() - 1;

        if (others == 0) {
            return firstMessage;
        }
        return firstMessage + " and " + others + " others interacted with your posts.";
    }

    private Long extractUserId(String key) {
        Matcher m = USER_ID_PATTERN.matcher(key);
        return m.matches() ? Long.parseLong(m.group(1)) : null;
    }
}
