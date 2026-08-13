package com.mycom.petcoupon.experiment.coupon.redis;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponQueueProcessor {
	
	private final RedisCouponQueueService redisCouponQueueService;
    private final RedisCouponStockService redisCouponStockService;

    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
	
    
    // Queue 에서 다음 한 건을 처리
    public boolean processNext(Long couponId) {
    	
        String queueMember = redisCouponQueueService.getFirstRequest(couponId);

        if (queueMember == null) {
            return false;
        }

        return processOne(couponId, queueMember);
    }
    
    // Queue 가 빌 떄까지 순서대로 처리 
    public void processAll(Long couponId) {

    	while (true) {

            String queueMember = redisCouponQueueService.getFirstRequest(couponId);

            if (queueMember == null) {
                break;
            }

            boolean processed = processOne(couponId, queueMember);

            if (!processed) {
                redisCouponQueueService.clear(couponId);
                break;
            }
        }
    }
    
    // Queue 의 첫 번째 요청 하나를 실제 발급 처리
    private boolean processOne(Long couponId, String queueMember) {
    	
    	String[] parts = queueMember.split(":", 2);
    	
    	Long userId = Long.valueOf(parts[0]);
        String requestId = parts[1];
        
        // Redis Lua Script
        Long result = redisCouponStockService.decreaseStock(couponId);
        
        // Redis 재고 키가 존재하지 않음
        if (result == -1) {
            throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
        }
        
        // 재고 소진 
        if (result == 0) {
            return false;
        }
        
        // 재고 차감 성공한 경우에만 Queue에서 제거
        redisCouponQueueService.removeFirst(couponId);

        // DB 저장
        saveIssue(
                couponId,
                userId,
                requestId
        );
        
        return true;
    }
    
    private void saveIssue(Long couponId, Long userId, String requestId) {
    	couponIssueRepository.save(
                CouponIssue.builder()
                        .coupon(couponRepository.getReferenceById(couponId))
                        .user(userRepository.getReferenceById(userId))
                        .requestId(requestId)
                        .build()
        );
    }
}
