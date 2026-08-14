package com.mycom.petcoupon.experiment.coupon.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.coupon.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponResponse;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.global.exception.CommonErrorCode;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponExperimentServiceImpl implements CouponExperimentService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    
    private final RedisCouponStockService redisCouponStockService;

    @Transactional
    @Override
    public CreateCouponResponse create(CreateCouponRequest request) {
        if (request == null || request.quantity() == null || request.quantity() <= 0) {
        	throw new GeneralException(CommonErrorCode.BAD_REQUEST);
        }

        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = couponRepository.saveAndFlush(Coupon.builder()
                .name("experiment-coupon-" + UUID.randomUUID().toString().substring(0, 8))
                .issueStartAt(now.minusMinutes(1))
                .issueEndAt(now.plusDays(1))
                .limitPerMember(1)
                .status("OPEN")
                .build());
        CouponStock stock = couponStockRepository.save(CouponStock.builder()
                .couponId(coupon.getId())
                .quantity(request.quantity())
                .build());
        return CreateCouponResponse.builder()
                .couponId(stock.getCouponId())
                .quantity(stock.getTotalQuantity())
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public CouponStatusResponse getStatus(Long couponId) {
        CouponStock stock = findStock(couponId);
        long issueCount = couponIssueRepository.countByCoupon_Id(couponId);
        
        Long redisRemainingQuantity = redisCouponStockService.getRemainingStock(couponId);
        
        boolean dbConsistent =
                stock.getIssuedQuantity() == issueCount
                && stock.getTotalQuantity() - stock.getRemainingQuantity() == issueCount;
        
        boolean redisConsistent =
                redisRemainingQuantity == null
                || stock.getTotalQuantity() - redisRemainingQuantity == issueCount;

        
        boolean consistent = dbConsistent && redisConsistent;

        return CouponStatusResponse.builder()
                .couponId(couponId)
                .totalQuantity(stock.getTotalQuantity())
                .dbRemainingQuantity(stock.getRemainingQuantity())
                .redisRemainingQuantity(redisRemainingQuantity)
                .issueCount(issueCount)
                .consistent(consistent)
                .build();
    }

    @Transactional
    @Override
    public CouponStatusResponse reset(Long couponId) {
        CouponStock stock = findStock(couponId);
        couponIssueRepository.deleteByCoupon_Id(couponId);
        stock.reset();

        return CouponStatusResponse.builder()
                .couponId(couponId)
                .totalQuantity(stock.getTotalQuantity())
                .dbRemainingQuantity(stock.getRemainingQuantity())
                .redisRemainingQuantity(null)
                .issueCount(0L)
                .consistent(true)
                .build();
    }

    private CouponStock findStock(Long couponId) {
        return couponStockRepository.findById(couponId)
                .orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));
    }

}
