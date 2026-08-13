package com.mycom.petcoupon.experiment.coupon.redis;

public interface RedisCouponStockService {

	// DB 재고를 Redis에 초기화
	void initialize(Long couponId);
	
	// Lua Script를 이용해 재고를 원자적으로 차감
	Long decreaseStock(Long couponId);

	// Redis에 저장된 현재 재고 조회
	Long getRemainingStock(Long couponId);

	// 쿠폰 재고의 Redis Key 조회
	String getKey(Long couponId);
	
	// Redis 재고 삭제
	void delete(Long couponId);
}
