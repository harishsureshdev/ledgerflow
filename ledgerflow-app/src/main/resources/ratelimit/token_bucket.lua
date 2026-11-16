-- Token bucket, evaluated atomically inside Redis (no race between concurrent requests).
-- KEYS[1]  bucket key
-- ARGV[1]  capacity (max tokens)
-- ARGV[2]  refill rate, tokens per millisecond
-- Returns { allowed (1/0), tokens remaining (floored), retry-after ms }
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_ms = tonumber(ARGV[2])

-- Use Redis' clock so every app instance agrees on time
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill_per_ms)

local allowed = 0
local retry_ms = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_ms = math.ceil((1 - tokens) / refill_per_ms)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
-- Forget idle buckets once they would be full again anyway
redis.call('PEXPIRE', key, math.ceil(capacity / refill_per_ms) + 1000)

return { allowed, math.floor(tokens), retry_ms }
