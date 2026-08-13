package com.mycom.petcoupon.experiment.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisLuaConfig {

	// 재고 확인과 차감을 하나의 원자적 연산으로 처리하는 Lua Script
    @Bean
    public DefaultRedisScript<Long> decreaseStockScript() {

        String script = """
                local stock = redis.call('GET', KEYS[1])

        		-- 재고 키가 존재하지 않는 경우
                if not stock then
                    return -1
                end
        		
        		-- 재고가 소진된 경우
                if tonumber(stock) <= 0 then
                    return 0
                end

                -- 재고 차감
                redis.call('DECR', KEYS[1])

                return 1
                """;

        return new DefaultRedisScript<>(script, Long.class);
    }
}