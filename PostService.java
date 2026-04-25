package com.grid07.service;

import com.grid07.dto.CreateCommentDTO;
import com.grid07.dto.CreatePostDTO;
import com.grid07.dto.LikeDTO;
import com.grid07.entity.Comment;
import com.grid07.entity.Post;
import com.grid07.repository.BotRepository;
import com.grid07.repository.CommentRepository;
import com.grid07.repository.PostRepository;
import com.grid07.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates every API action:
 *   1. Redis guardrails are checked FIRST (gatekeeper).
 *   2. Only if Redis allows the action do we commit to PostgreSQL (source of truth).
 *
 * This ordering is the assignment's core Data Integrity requirement:
 *   "database transactions are only committed if the Redis guardrails allow the action."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository       postRepository;
    private final CommentRepository    commentRepository;
    private final UserRepository       userRepository;
    private final BotRepository        botRepository;
    private final RedisGuardrailService redis;

    // ── Phase 1: Create Post ─────────────────────────────────────────────────

    @Transactional
    public Post createPost(CreatePostDTO dto) {
        validateAuthor(dto.getAuthorId(), dto.getAuthorType());

        Post post = Post.builder()
            .authorId(dto.getAuthorId())
            .authorType(dto.getAuthorType().toUpperCase())
            .content(dto.getContent())
            .build();

        return postRepository.save(post);
    }

    // ── Phase 1 + Phase 2: Add Comment ──────────────────────────────────────

    /**
     * Full guardrail pipeline for a bot comment:
     *   Step 1 — Vertical cap: depth must be ≤ 20.
     *   Step 2 — Cooldown cap: bot cannot interact with this human within 10 min.
     *   Step 3 — Horizontal cap (atomic Lua): post cannot exceed 100 bot replies.
     *   Step 4 — Save to PostgreSQL.
     *   Step 5 — Update virality score in Redis.
     *   Step 6 — Trigger notification engine.
     *
     * For human comments steps 1-3 are skipped; only virality + DB apply.
     */
    @Transactional
    public Comment addComment(Long postId, CreateCommentDTO dto) {
        // Verify post exists
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Post not found: " + postId));

        validateAuthor(dto.getAuthorId(), dto.getAuthorType());

        boolean isBot = "BOT".equalsIgnoreCase(dto.getAuthorType());

        if (isBot) {
            applyBotGuardrails(postId, dto);
        }

        // ── Persist to PostgreSQL ───────────────────────────────────────────
        Comment comment = Comment.builder()
            .postId(postId)
            .authorId(dto.getAuthorId())
            .authorType(dto.getAuthorType().toUpperCase())
            .content(dto.getContent())
            .depthLevel(dto.getDepthLevel())
            .build();

        Comment saved = commentRepository.save(comment);
        log.debug("Comment {} saved to DB for post {}", saved.getId(), postId);

        // ── Update virality in Redis ────────────────────────────────────────
        if (isBot) {
            redis.recordBotReply(postId);
        } else {
            redis.recordHumanComment(postId);
        }

        // ── Notification engine (bot interactions only) ─────────────────────
        if (isBot && dto.getTargetUserId() != null) {
            String botName = botRepository.findById(dto.getAuthorId())
                .map(b -> b.getName())
                .orElse("Bot#" + dto.getAuthorId());
            redis.handleBotNotification(dto.getTargetUserId(), botName, postId);
        }

        return saved;
    }

    // ── Phase 1 + Phase 2: Like Post ────────────────────────────────────────

    @Transactional
    public String likePost(Long postId, LikeDTO dto) {
        // Verify post exists
        postRepository.findById(postId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Post not found: " + postId));

        // Verify user exists
        userRepository.findById(dto.getUserId())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "User not found: " + dto.getUserId()));

        // Update virality (+20 for human like)
        redis.recordHumanLike(postId);

        log.info("Post {} liked by user {}. Virality score: {}",
            postId, dto.getUserId(), redis.getViralityScore(postId));

        return String.format(
            "Post %d liked by user %d. Virality score: %d",
            postId, dto.getUserId(), redis.getViralityScore(postId)
        );
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void validateAuthor(Long authorId, String authorType) {
        if ("BOT".equalsIgnoreCase(authorType)) {
            botRepository.findById(authorId)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Bot not found: " + authorId));
        } else if ("USER".equalsIgnoreCase(authorType)) {
            userRepository.findById(authorId)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "User not found: " + authorId));
        } else {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "authorType must be 'USER' or 'BOT'");
        }
    }

    /**
     * Runs the three Redis guardrails for bot comments.
     * Throws 429 if any check fails — the @Transactional annotation
     * ensures no DB write has happened yet at this point.
     */
    private void applyBotGuardrails(Long postId, CreateCommentDTO dto) {

        // ── Guardrail 1: Vertical cap ──────────────────────────────────────
        if (!redis.isDepthAllowed(dto.getDepthLevel())) {
            log.warn("Bot {} blocked — vertical cap exceeded (depth {})",
                dto.getAuthorId(), dto.getDepthLevel());
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Vertical cap exceeded: max depth is 20, requested depth is " + dto.getDepthLevel()
            );
        }

        // ── Guardrail 2: Cooldown cap ──────────────────────────────────────
        if (dto.getTargetUserId() != null) {
            boolean allowed = redis.tryAcquireBotCooldown(dto.getAuthorId(), dto.getTargetUserId());
            if (!allowed) {
                log.warn("Bot {} blocked — cooldown active for human {}",
                    dto.getAuthorId(), dto.getTargetUserId());
                throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Cooldown active: bot " + dto.getAuthorId() +
                    " cannot interact with user " + dto.getTargetUserId() + " yet"
                );
            }
        }

        // ── Guardrail 3: Horizontal cap (atomic Lua) ───────────────────────
        long newCount = redis.tryIncrementBotCount(postId);
        if (newCount == -1) {
            log.warn("Bot {} blocked — horizontal cap (100) reached on post {}",
                dto.getAuthorId(), postId);
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Horizontal cap exceeded: post " + postId + " already has 100 bot replies"
            );
        }

        log.debug("Bot {} passed all guardrails. Bot count on post {}: {}",
            dto.getAuthorId(), postId, newCount);
    }
}
