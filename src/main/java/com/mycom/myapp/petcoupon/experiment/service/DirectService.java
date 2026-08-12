package com.mycom.petcoupon.experiment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.entity.CouponIssue;
import com.mycom.petcoupon.experiment.entity.CouponStock;
import com.mycom.petcoupon.experiment.exception.CouponIssueFailedException;
import com.mycom.petcoupon.experiment.exception.CouponNotFoundException;
import com.mycom.petcoupon.experiment.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.repository.CouponStockRepository;

@Service
public class DirectService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;

    public DirectService(
            CouponStockRepository couponStockRepository,
            CouponIssueRepository couponIssueRepository) {
        this.couponStockRepository = couponStockRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public void issue(Long couponId, Long userId) {
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> new CouponNotFoundException(couponId));

        if (stock.getRemainingQuantity() <= 0) {
            throw new CouponIssueFailedException("쿠폰 재고가 없습니다.");
        }

        stock.decrease();
        couponIssueRepository.save(new CouponIssue(couponId, userId));
    }
}
