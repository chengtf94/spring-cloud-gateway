redis.replicate_commands()

-- 令牌key、时间戳key
local tokens_key = KEYS[1]
local timestamp_key = KEYS[2]

-- 令牌生成速率、令牌桶容量、当前时间戳、请求令牌数、
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = redis.call('TIME')[1]
local requested = tonumber(ARGV[4])
local fill_time = capacity/rate
local ttl = math.floor(fill_time * 2)

-- 查询Redis：剩余令牌数、上次令牌生成时间戳
local last_tokens = tonumber(redis.call("get", tokens_key))
if last_tokens == nil then
  last_tokens = capacity
end
local last_refreshed = tonumber(redis.call("get", timestamp_key))
if last_refreshed == nil then
  last_refreshed = 0
end

--  生成令牌：基于时间差 * 漏水速率确定生成令牌数（核心公式）
local delta = math.max(0, now - last_refreshed)
local filled_tokens = math.min(capacity, last_tokens+(delta*rate))

-- 判断是否达到限流阈值
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens
local allowed_num = 0
if allowed then
  new_tokens = filled_tokens - requested
  allowed_num = 1
end

-- 更新Redis：剩余令牌数、上次令牌生成时间戳
if ttl > 0 then
  redis.call("setex", tokens_key, ttl, new_tokens)
  redis.call("setex", timestamp_key, ttl, now)
end

return { allowed_num, new_tokens }