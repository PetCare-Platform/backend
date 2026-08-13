package com.mycom.petcoupon.experiment.coupon.redis;

public interface RedisCouponQueueService {

	// 요청을 Redis Queue에 등록
	Long enqueue(Long couponId, Long userId, String requestId);
	
	// Queue의 가장 앞에 있는 요청 조회
	String getFirstRequest(Long couponId);

	// Queue의 가장 앞에 있는 요청 제거
	void removeFirst(Long couponId);
	
	// 해당 쿠폰의 Queue 및 순번 초기화
	void clear(Long couponId);
}
