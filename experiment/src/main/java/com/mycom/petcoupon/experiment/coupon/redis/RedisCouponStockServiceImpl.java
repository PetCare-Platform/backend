package com.mycom.petcoupon.experiment.coupon.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.global.config.RedisLuaConfig;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponStockServiceImpl implements RedisCouponStockService {
	
	private final StringRedisTemplate redisTemplate;
    private final CouponStockRepository couponStockRepository;
    
    private final RedisLuaConfig redisLuaConfig;
    
	@Override
	public void initialize(Long couponId) {
		CouponStock stock = couponStockRepository.findById(couponId)
				.orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));
		
		String key = getKey(couponId);
		
		redisTemplate.opsForValue().set(key, String.valueOf(stock.getRemainingQuantity()));
	}

	@Override
	public Long decreaseStock(Long couponId, String requestId, Long userId) {
		return redisTemplate.execute(
				redisLuaConfig.decreaseStockScript(),
                List.of(
                		getKey(couponId),
                		getRequestKey(couponId, requestId),
                		getUserKey(couponId, userId)
                		
                ),
                requestId, 
                String.valueOf(userId)
        );
	}
	
	@Override
    public void restoreStock(Long couponId, String requestId, Long userId) {
		redisTemplate.execute(
				redisLuaConfig.restoreStockScript(),
                List.of(
                        getKey(couponId),
                        getRequestKey(couponId, requestId),
                        getUserKey(couponId, userId)
                )
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
    public String getRequestKey(Long couponId, String requestId) {
		
        return "coupon:" + couponId + ":request:" + requestId;
    }


    @Override
    public String getUserKey(Long couponId, Long userId) {
    	return "coupon:" + couponId + ":user:" + userId;
    }
    
	@Override
	public void delete(Long couponId) {
		
		redisTemplate.delete(getKey(couponId));
	}

	@Override
	public Long increaseStock(Long couponId) {
		return redisTemplate.opsForValue().increment(getKey(couponId));
	}
}
