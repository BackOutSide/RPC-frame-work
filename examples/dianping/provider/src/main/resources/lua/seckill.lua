-- KEYS: stock, accepted-user order id, voucher metadata, order status, order stream.
-- ARGV: user id, order id, voucher id, current epoch millis.
local prior = redis.call('GET', KEYS[2])
if prior then
    return 'ACCEPTED:' .. prior
end

local startsAt = redis.call('HGET', KEYS[3], 'startsAt')
local endsAt = redis.call('HGET', KEYS[3], 'endsAt')
local stock = redis.call('GET', KEYS[1])
if not startsAt or not endsAt or not stock then
    return 'NOT_FOUND'
end

local now = tonumber(ARGV[4])
if now < tonumber(startsAt) then
    return 'NOT_STARTED'
end
if now > tonumber(endsAt) then
    return 'ENDED'
end
if tonumber(stock) <= 0 then
    return 'SOLD_OUT'
end

redis.call('DECR', KEYS[1])
redis.call('SET', KEYS[2], ARGV[2])
redis.call('HSET', KEYS[4], 'userId', ARGV[1], 'voucherId', ARGV[3])
-- Keep idempotency state through the sale plus a seven-day reconciliation window.
local forgetAfter = tonumber(endsAt) + 604800000
redis.call('PEXPIREAT', KEYS[2], forgetAfter)
redis.call('PEXPIREAT', KEYS[4], forgetAfter)
redis.call('XADD', KEYS[5], '*',
    'orderId', ARGV[2], 'voucherId', ARGV[3], 'userId', ARGV[1])
return 'ACCEPTED:' .. ARGV[2]
