package com.mycom.petcoupon.experiment.service;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.dto.CreateCouponResponse;
import com.mycom.petcoupon.experiment.entity.Coupon;
import com.mycom.petcoupon.experiment.entity.CouponStock;
import com.mycom.petcoupon.experiment.exception.CouponNotFoundException;
import com.mycom.petcoupon.experiment.exception.InvalidExperimentRequestException;
import com.mycom.petcoupon.experiment.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.repository.CouponRepository;
import com.mycom.petcoupon.experiment.repository.CouponStockRepository;

@Service
public class ExperimentCouponService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final StringRedisTemplate redisTemplate;

    public ExperimentCouponService(
            CouponStockRepository couponStockRepository,
            CouponIssueRepository couponIssueRepository,
            CouponRepository couponRepository,
            StringRedisTemplate redisTemplate) {
        this.couponStockRepository = couponStockRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.couponRepository = couponRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public CreateCouponResponse create(CreateCouponRequest request) {
        if (request == null || request.quantity() == null || request.quantity() <= 0) {
            throw new InvalidExperimentRequestException("쿠폰 수량은 1 이상이어야 합니다.");
        }

        Coupon coupon = couponRepository.saveAndFlush(
                new Coupon("실험쿠폰-" + UUID.randomUUID().toString().substring(0, 8)));
        CouponStock stock = couponStockRepository.save(new CouponStock(coupon.getId(), request.quantity()));
        return new CreateCouponResponse(stock.getCouponId(), stock.getTotalQuantity());
    }

    @Transactional(readOnly = true)
    public CouponStatusResponse getStatus(Long couponId) {
        CouponStock stock = findStock(couponId);
        long issueCount = couponIssueRepository.countByCouponId(couponId);
        boolean consistent = stock.getTotalQuantity() - stock.getRemainingQuantity() == issueCount;

        return new CouponStatusResponse(
                couponId,
                stock.getTotalQuantity(),
                stock.getRemainingQuantity(),
                null,
                issueCount,
                consistent);
    }

    @Transactional
    public CouponStatusResponse reset(Long couponId) {
        CouponStock stock = findStock(couponId);
        couponIssueRepository.deleteByCouponId(couponId);
        stock.reset();
        redisTemplate.delete(redisStockKey(couponId));

        return new CouponStatusResponse(
                couponId,
                stock.getTotalQuantity(),
                stock.getRemainingQuantity(),
                null,
                0L,
                true);
    }

    private CouponStock findStock(Long couponId) {
        return couponStockRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(couponId));
    }

    private String redisStockKey(Long couponId) {
        return "experiment:coupon:%d:stock".formatted(couponId);
    }
}
