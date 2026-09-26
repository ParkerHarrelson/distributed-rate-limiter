-- Fixed window counter (reference implementation).
--
-- KEYS[1]  key for this client/rule, e.g. "rl:user-global:alice"
-- ARGV[1]  rate       max requests per window
-- ARGV[2]  period_ms  window length in milliseconds
--
-- Returns { allowed, limit, remaining, retry_after_ms, reset_after_ms }
--
-- State is a single hash { w = window_index, c = count } so that the whole
-- algorithm touches exactly one key. That matters for Redis Cluster, where a
-- script may only touch keys it declared up front (all in the same hash slot).

local rate      = tonumber(ARGV[1])
local period_ms = tonumber(ARGV[2])

-- redis TIME returns { seconds, microseconds }.
local t      = redis.call('TIME')
local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)

local window   = math.floor(now_ms / period_ms)
local reset_ms = (window + 1) * period_ms - now_ms

local stored = redis.call('HGET', KEYS[1], 'w')
if stored == false or tonumber(stored) ~= window then
  -- New window for this key: reset the counter. Expire the whole hash when
  -- the window ends so idle keys do not accumulate.
  redis.call('HSET', KEYS[1], 'w', window, 'c', 0)
  redis.call('PEXPIRE', KEYS[1], reset_ms)
end

local count = tonumber(redis.call('HGET', KEYS[1], 'c'))
if count >= rate then
  return { 0, rate, 0, reset_ms, reset_ms }
end

count = redis.call('HINCRBY', KEYS[1], 'c', 1)
return { 1, rate, rate - count, 0, reset_ms }
