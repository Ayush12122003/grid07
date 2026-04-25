# Grid07 — Spring Boot Core API & Guardrails

## Tech Stack
- **Java 17** · **Spring Boot 3.2** · **PostgreSQL 16** · **Redis 7** · **Lettuce** (Redis client)

---

## Quick Start

```bash
# 1. Start Postgres + Redis
docker-compose up -d

# 2. Run the application
./mvnw spring-boot:run

# 3. Import Grid07.postman_collection.json into Postman and run
```

---

## Project Structure

```
src/main/java/com/grid07/
├── Grid07Application.java          # Entry point + @EnableScheduling
├── config/
│   ├── RedisConfig.java            # StringRedisTemplate bean
│   └── RedisKeys.java              # Central registry of all Redis key patterns
├── controller/
│   ├── PostController.java         # POST /api/posts, /comments, /like + GET /virality
│   ├── SetupController.java        # POST /api/users, /api/bots (for test setup)
│   └── GlobalExceptionHandler.java # Maps ResponseStatusException → clean JSON
├── dto/
│   ├── CreatePostDTO.java
│   ├── CreateCommentDTO.java
│   └── LikeDTO.java
├── entity/
│   ├── User.java                   # id, username, is_premium
│   ├── Bot.java                    # id, name, persona_description
│   ├── Post.java                   # id, author_id, author_type, content, created_at
│   └── Comment.java                # id, post_id, author_id, depth_level, created_at
├── repository/                     # Standard JpaRepository for each entity
├── service/
│   ├── PostService.java            # Business logic — orchestrates DB + Redis
│   └── RedisGuardrailService.java  # All Redis operations (virality, locks, notifications)
└── scheduler/
    └── NotificationSweeper.java    # @Scheduled CRON — batches pending notifications
```

---

## Phase 1 — Core API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/posts` | Create a post (User or Bot author) |
| POST | `/api/posts/{id}/comments` | Add a comment (bot guardrails enforced) |
| POST | `/api/posts/{id}/like` | Like a post (human only; +20 virality) |
| GET  | `/api/posts/{id}/virality` | Debug — view virality score + bot count |
| POST | `/api/users` | Create a test user |
| POST | `/api/bots` | Create a test bot |

---

## Phase 2 — Redis Virality Engine & Atomic Locks

### Virality Scoring (real-time)

| Event | Points | Redis key |
|-------|--------|-----------|
| Bot reply | +1 | `post:{id}:virality_score` |
| Human like | +20 | same |
| Human comment | +50 | same |

Redis `INCR` is atomic by design — no locks needed for the score itself.

### Atomic Locks — Thread Safety Guarantee

This is the most critical section of the assignment. All three guardrails must be race-condition-proof.

#### Horizontal Cap (max 100 bot replies per post)

**Problem:** With 200 concurrent requests, a naive `GET → check → INCR` sequence has a race window where two threads both read 99, both pass the check, and both increment to 100/101.

**Solution — Lua script executed via `EVAL`:**

```lua
local count = redis.call('INCR', KEYS[1])
if count > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    return -1
end
return count
```

Redis is **single-threaded**. An `EVAL` call sends the entire Lua script as one atomic unit — Redis executes it completely before processing any other command. This eliminates the race window entirely:

- Thread A and Thread B both fire `EVAL` simultaneously.
- Redis queues them and executes them **one at a time**.
- If count reaches 100, any subsequent INCR is immediately rolled back with a DECR inside the same atomic script.
- **The counter can never exceed 100**, regardless of how many concurrent callers there are.

This is tested against 200 simultaneous requests in Phase 4.

#### Cooldown Cap (bot ↔ human, 10-minute TTL)

Uses Redis `SET NX EX` (Set if Not Exists with expiry):

```
SET cooldown:bot_1:human_1  1  EX 600  NX
```

`NX` makes this atomic — only **one** of many concurrent callers can set the key. All others get a `nil` return and are blocked. No Lua script needed because `SET NX EX` is natively atomic in Redis.

#### Vertical Cap (max depth 20)

Pure in-memory check — the `depthLevel` is supplied by the caller and validated before any Redis or DB call. No atomicity concern because `depthLevel` is not a shared counter.

### Ordering Guarantee (Data Integrity)

Every API flow follows this strict order:

```
1. Redis guardrails checked first (gatekeeper)
      ↓ allowed
2. PostgreSQL write committed (source of truth)
      ↓ committed
3. Redis virality score updated
4. Redis notification engine triggered
```

If any Redis guardrail throws 429, the `@Transactional` method exits before any DB write occurs — the database never sees rejected requests.

---

## Phase 3 — Notification Engine

### Throttler Logic (per bot interaction)

```
SET NX EX 900  notif_cooldown:user_{id}
  → success (first notif in 15 min): log "Push Notification Sent to User X"
  → fail (within 15 min cooldown):   RPUSH user:{id}:pending_notifs "Bot X replied..."
```

### CRON Sweeper (`NotificationSweeper.java`)

- Runs every **5 minutes** (`fixedRate = 300_000 ms`).
- Uses `KEYS user:*:pending_notifs` to discover users with pending notifications.
- For each user: `LRANGE 0 -1` → drain messages → `DEL` list → log summary.
- Summary format: `"Bot Alpha and 3 others interacted with your posts."`

---

## Phase 4 — Corner Cases

| Test | How we handle it |
|------|-----------------|
| **Race condition (200 bots, 1 post)** | Lua script makes INCR + check atomic in Redis. Stops at exactly 100. |
| **Statelessness** | Zero `HashMap`/`static` variables. All state in Redis or Postgres. |
| **Data integrity** | Redis is gatekeeper — DB writes only happen after Redis allows. `@Transactional` rolls back if Redis throws before DB write. |

---

## Redis Key Reference

| Key pattern | Type | TTL | Purpose |
|---|---|---|---|
| `post:{id}:virality_score` | String (integer) | none | Cumulative virality |
| `post:{id}:bot_count` | String (integer) | none | Bot reply counter |
| `cooldown:bot_{id}:human_{id}` | String | 10 min | Bot-human cooldown |
| `notif_cooldown:user_{id}` | String | 15 min | Notification cooldown |
| `user:{id}:pending_notifs` | List | none (cleared by sweeper) | Buffered notifications |
