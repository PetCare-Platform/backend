package com.mycom.petcoupon.experiment.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisLuaConfig {

	/**
	 * 중복 요청 확인 + 중복 사용자 확인 + 재고 차감을 원자적으로 처리 
	 * KEYS[1] = 재고 키, KEYS[2] = requestId 중복 확인 키,
	 * KEYS[3] = couponId + userId 중복 확인 키
	 * 
	 * ARGV[1] = requestId, ARGV[2] = userId
	 */
    public DefaultRedisScript<Long> decreaseStockScript() {

        String script = """
                local stock = redis.call('GET', KEYS[1])

        		-- 재고 키가 존재하지 않는 경우
                if not stock then
                    return -1
                end
        		
        		-- requestId 중복 확인 
                if redis.call('EXISTS', KEYS[2]) == 1 then
                    return -3
                end
                
                -- 동일 사용자 중복 확인
                if redis.call('EXISTS', KEYS[3]) == 1 then
                    return -4
                end
                
        		-- 재고가 소진된 경우
                if tonumber(stock) <= 0 then
                    return -2
                end

                -- 재고 차감
                redis.call('DECR', KEYS[1])

        		-- 중복 방지 키 등록
                redis.call('SET', KEYS[2], ARGV[1])
                redis.call('SET', KEYS[3], ARGV[2])
                
                return 1
                """;

        return new DefaultRedisScript<>(script, Long.class);
    }
    
    /*
     * DB 저장 실패 시 Redis 에서 수행한 작업을 원상복구
     * KEYS[1] = 재고 키, KEYS[2] = requestId 중복 키, KEYS[3] = 사용자 중복 키
     */
    public DefaultRedisScript<Long> restoreStockScript() {

        String script = """
                redis.call('INCR', KEYS[1])
                redis.call('DEL', KEYS[2])
                redis.call('DEL', KEYS[3])

                return 1
                """;

        return new DefaultRedisScript<>(script, Long.class);
    }
}