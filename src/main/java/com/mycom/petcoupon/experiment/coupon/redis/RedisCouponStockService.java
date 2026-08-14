package com.mycom.petcoupon.experiment.coupon.redis;

public interface RedisCouponStockService {

	// DB 재고를 Redis에 초기화
	void initialize(Long couponId);
	
	// Lua Script를 이용해 재고를 원자적으로 차감
	Long decreaseStock(Long couponId, String requestId, Long userId);

	// DB 저장 실패 시 보상 메서드 
	void restoreStock(Long couponId, String requestId, Long userId);
	
	// Redis에 저장된 현재 재고 조회
	Long getRemainingStock(Long couponId);

	// 쿠폰 재고의 Redis Key 조회
	String getKey(Long couponId);
	
	String getRequestKey(Long couponId, String requestId);

    String getUserKey(Long couponId, Long userId);
    
	// Redis 재고 삭제
	void delete(Long couponId);
	
	Long increaseStock(Long couponId);
}
