package com.mycom.petcoupon.experiment.coupon.redis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
		
		delete(couponId);
		
		redisTemplate.opsForValue().set(getKey(couponId), String.valueOf(stock.getRemainingQuantity()));
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

        Set<String> keys = new HashSet<>();

        keys.add(getKey(couponId));

        Set<String> requestKeys = redisTemplate.keys("coupon:" + couponId + ":request:*");
        Set<String> userKeys = redisTemplate.keys("coupon:" + couponId + ":user:*");

        if (requestKeys != null) {
            keys.addAll(requestKeys);
        }

        if (userKeys != null) {
            keys.addAll(userKeys);
        }

        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

	@Override
	public Long increaseStock(Long couponId) {
		return redisTemplate.opsForValue().increment(getKey(couponId));
	}
}
