package com.mycom.petcoupon.experiment.coupon.service;

import org.springframework.stereotype.Service;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponQueueProcessor;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponQueueService;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RedisCouponIssueServiceImpl implements CouponIssueService {

	private final RedisCouponQueueService redisCouponQueueService;
	private final RedisCouponQueueProcessor redisCouponQueueProcessor;
	
	private final CouponIssueRepository couponIssueRepository;
	
    
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
    	
    	rejectDuplicate(couponId, request);
    	
    	// Redis Queue 등록 
    	redisCouponQueueService.enqueue(
                couponId,
                request.userId(),
                request.requestId()
        );
    	
    	// Queue 에 들어온 요청들을 순서대로 처리
    	redisCouponQueueProcessor.processAll(couponId);
    	
    	boolean issued = couponIssueRepository.existsByRequestId(request.requestId());

        if (!issued) {
        	return CouponIssueResponse.builder()
                    .couponId(couponId)
                    .userId(request.userId())
                    .requestId(request.requestId())
                    .result(CouponIssueResult.WAITING)
                    .build();
        }

        return CouponIssueResponse.success(
                couponId,
                request
        );
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.REDIS;
    }
    
    private void rejectDuplicate(Long couponId, CouponIssueRequest request) {
    	if (couponIssueRepository.existsByRequestId(request.requestId())) {
    		throw new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
    	}
    	if (couponIssueRepository.existsByCoupon_IdAndUser_Id(couponId, request.userId())) {
            throw new GeneralException(ExperimentErrorCode.DUPLICATE_USER);
        }
    }
}
