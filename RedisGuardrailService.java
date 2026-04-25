package com.grid07.service;

import com.grid07.config.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * All Redis interactions live here. Every public method that needs
 * to be atomic uses a Lua script executed via EVAL so that Redis
 * handles it as a single, uninterruptible operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisGuardrailService {

    private static final int BOT_REPLY_POINTS     = 1;
    private static final int HUMAN_LIKE_POINTS     = 20;
    private static final int HUMAN_COMMENT_POINTS  = 50;

    private static final int  HORIZONTAL_CAP       = 100;
    private static final int  VERTICAL_CAP         = 20;
    private static final long COOLDOWN_TTL_MINUTES  = 10;
    private static final long NOTIF_TTL_MINUTES     = 15;

    private final StringRedisTemplate redis;

    // ────────────────────────────────────────────────────────────────────────
    // Phase 2-A: Virality Score
    // ────────────────────────────────────────────────────────────────────────

    public void recordBotReply(Long postId) {
        incrementVirality(postId, BOT_REPLY_POINTS);
    }

    public void recordHumanLike(Long postId) {
        incrementVirality(postId, HUMAN_LIKE_POINTS);
    }

    public void recordHumanComment(Long postId) {
        incrementVirality(postId, HUMAN_COMMENT_POINTS);
    }

    private void incrementVirality(Long postId, int points) {
        String key = RedisKeys.viralityScore(postId);
        Long score = redis.opsForValue().increment(key, points);
        log.debug("Virality score for post {} → {}", postId, score);
    }

    public Long getViralityScore(Long postId) {
        String raw = redis.opsForValue().get(RedisKeys.viralityScore(postId));
        return raw == null ? 0L : Long.parseLong(raw);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase 2-B: Horizontal Cap (atomic INCR + check via Lua)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Atomically increments the bot-reply counter for a post.
     * Returns the new count, or -1 if the cap would be exceeded.
     *
     * Lua script explanation:
     *   1. INCR the counter (atomic).
     *   2. If the new value > cap, DECR to roll back and return -1.
     *   3. Otherwise return the new value.
     *
     * Because Redis is single-threaded and Lua runs atomically,
     * 200 concurrent EVAL calls will never allow count > 100.
     */
    public long tryIncrementBotCount(Long postId) {
        String luaScript =
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count > tonumber(ARGV[1]) then " +
            "    redis.call('DECR', KEYS[1]) " +
            "    return -1 " +
            "end " +
            "return count";

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = redis.execute(
            script,
            List.of(RedisKeys.botCount(postId)),
            String.valueOf(HORIZONTAL_CAP)
        );
        return result == null ? -1L : result;
    }

    public long getBotCount(Long postId) {
        String raw = redis.opsForValue().get(RedisKeys.botCount(postId));
        return raw == null ? 0L : Long.parseLong(raw);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase 2-B: Vertical Cap (simple check — depth is determined at call site)
    // ────────────────────────────────────────────────────────────────────────

    public boolean isDepthAllowed(int depthLevel) {
        return depthLevel <= VERTICAL_CAP;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase 2-B: Cooldown Cap (bot <-> human, 10-minute TTL)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Atomically checks whether a cooldown key exists and, if not, sets it.
     * Uses SET NX EX (set-if-not-exists with expiry) — one round-trip, fully atomic.
     *
     * Returns true  → interaction is allowed (key was absent, now set).
     * Returns false → interaction is blocked (key already existed).
     */
    public boolean tryAcquireBotCooldown(Long botId, Long humanId) {
        String key = RedisKeys.cooldown(botId, humanId);
        Boolean set = redis.opsForValue().setIfAbsent(
            key, "1", Duration.ofMinutes(COOLDOWN_TTL_MINUTES)
        );
        return Boolean.TRUE.equals(set);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase 3: Notification Engine
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Called every time a bot interacts with a user's post.
     *
     * Logic:
     *  - If the user has NO 15-min cooldown key → send immediately, set cooldown.
     *  - If the user HAS a cooldown key → push to their pending-notifs list.
     */
    public void handleBotNotification(Long userId, String botName, Long postId) {
        String cooldownKey = RedisKeys.notifCooldown(userId);
        String message     = String.format("Bot %s replied to your post %d", botName, postId);

        // SET NX EX — atomic check-and-set
        Boolean isFirstNotif = redis.opsForValue().setIfAbsent(
            cooldownKey, "1", Duration.ofMinutes(NOTIF_TTL_MINUTES)
        );

        if (Boolean.TRUE.equals(isFirstNotif)) {
            // No cooldown was active — send immediately
            log.info("[NOTIFICATION] Push Notification Sent to User {}: {}", userId, message);
        } else {
            // Cooldown active — buffer for batch delivery
            redis.opsForList().rightPush(RedisKeys.pendingNotifs(userId), message);
            log.debug("[NOTIFICATION] Buffered for user {}: {}", userId, message);
        }
    }

    /**
     * Called by the CRON sweeper. Returns all pending notification strings
     * for a user and atomically clears the list.
     */
    public List<String> drainPendingNotifs(Long userId) {
        String key = RedisKeys.pendingNotifs(userId);
        // LRANGE 0 -1 gets all elements; DEL removes the key
        List<String> messages = redis.opsForList().range(key, 0, -1);
        redis.delete(key);
        return messages == null ? List.of() : messages;
    }

    /**
     * Returns all Redis keys matching the pending-notifs pattern.
     * Used by the CRON sweeper to discover which users have pending messages.
     */
    public java.util.Set<String> getAllPendingNotifKeys() {
        return redis.keys(RedisKeys.pendingNotifsPattern());
    }
}
