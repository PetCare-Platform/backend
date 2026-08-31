package com.mycom.petcoupon.experiment.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponIssueServiceImpl implements CouponIssueService {
	
	private final RedisCouponStockService redisCouponStockService;

    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
    	
    	Long result = redisCouponStockService.decreaseStock(couponId, request.requestId(), request.userId());
    	
    
    	// Redis 재고 키 없음
        if (result == -1) {
            throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
        }

        // 재고 소진
        if (result == -2) {
            throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
        }
        
        // requestId 중복
        if (result == -3) {
            throw new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
        }
        
        // 동일 사용자 중복
        if (result == -4) {
            throw new GeneralException(ExperimentErrorCode.DUPLICATE_USER);
        }

        
        try {
        	couponIssueRepository.saveAndFlush(
                    CouponIssue.builder()
                            .coupon(couponRepository.getReferenceById(couponId))
                            .user(userRepository.getReferenceById(request.userId()))
                            .requestId(request.requestId())
                            .build()
            );
        } catch (Exception e) {
        	redisCouponStockService.restoreStock(couponId, request.requestId(), request.userId());
        	throw e;
		}
        
        return CouponIssueResponse.builder()
        		.couponId(couponId)
        		.userId(request.userId())
        		.requestId(request.requestId())
        		.result(CouponIssueResult.SUCCESS)
        		.build();
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.REDIS;
    }
}
