local count = redis.call('INCR', KEYS[1])
local windowMillis = tonumber(ARGV[2])
if count == 1 then
    redis.call('PEXPIRE', KEYS[1], windowMillis)
end
local remainingMillis = redis.call('PTTL', KEYS[1])
-- Repair a counter without expiry, should one have been created outside this script.
if remainingMillis < 0 then
    redis.call('PEXPIRE', KEYS[1], windowMillis)
    remainingMillis = windowMillis
end
if count > tonumber(ARGV[1]) then
    return math.max(1, math.ceil(remainingMillis / 1000))
end
return 0
