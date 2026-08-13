package com.mycom.petcoupon.experiment.coupon.redis;

import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponQueueServiceImpl implements RedisCouponQueueService {

	private final StringRedisTemplate redisTemplate;

	@Override
	public Long enqueue(Long couponId, Long userId, String requestId) {
		
		String sequenceKey = "coupon:sequence:" + couponId;
		String queueKey = "coupon:queue:" + couponId;
		
		// Redis 에서 순번을 발급 
		Long sequence = redisTemplate.opsForValue().increment(sequenceKey);
		
		String member = userId + ":" + requestId;
		
		// sequence 를 score 로 사용헤서 ZSET 에 등록 
		redisTemplate.opsForZSet().add(queueKey, member, sequence);
		
		return sequence;
	}

	@Override
	public String getFirstRequest(Long couponId) {
		
		 String queueKey = "coupon:queue:" + couponId;
		 
		 Set<String> first = redisTemplate.opsForZSet().range(queueKey, 0, 0);
		
		 if (first == null || first.isEmpty()) {
	            return null;
	     }
		 
		return first.iterator().next();
	}
	
	@Override
	public void removeFirst(Long couponId) {
		
		String queueKey = "coupon:queue:" + couponId;
		
		Set<String> first =redisTemplate.opsForZSet().range(queueKey, 0, 0);
		
		if (first == null || first.isEmpty()) {
            return;
        }
		
		redisTemplate.opsForZSet().remove(queueKey, first.iterator().next());
	}
	
	@Override
	public void clear(Long couponId) {
		
		redisTemplate.delete("coupon:sequence:" + couponId);
		redisTemplate.delete("coupon:queue:" + couponId);
	}
}
