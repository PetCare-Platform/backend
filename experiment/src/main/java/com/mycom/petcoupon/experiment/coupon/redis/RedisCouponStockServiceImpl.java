package com.mycom.petcoupon.experiment.coupon.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponStockServiceImpl implements RedisCouponStockService {
	
	private final StringRedisTemplate redisTemplate;
    private final CouponStockRepository couponStockRepository;
    
    private final DefaultRedisScript<Long> decreaseStockScript;
    
	@Override
	public void initialize(Long couponId) {
		CouponStock stock = couponStockRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));
		
		String key = getKey(couponId);
		
		redisTemplate.opsForValue().set(key, String.valueOf(stock.getRemainingQuantity()));
	}

	@Override
	public Long decreaseStock(Long couponId) {
		return redisTemplate.execute(
                decreaseStockScript,
                List.of(getKey(couponId))
        );
	}
	
	@Override
	public Long getRemainingStock(Long couponId) {
		
		String value = redisTemplate.opsForValue().get(getKey(couponId));
		
		return value == null ? null : Long.valueOf(value);
	}

	@Override
	public String getKey(Long couponId) {
		
		return "coupon:stock:" + couponId;
	}

	@Override
	public void delete(Long couponId) {
		
		redisTemplate.delete(getKey(couponId));
	}
}
