package com.mycom.petcoupon.experiment.coupon.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;
import com.mycom.petcoupon.experiment.global.exception.CouponIssueException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DirectCouponIssueServiceImpl implements CouponIssueService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    @Transactional
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        rejectDuplicate(couponId, request);

        // 의도적으로 비관적 락, 버전 검사, 조건부 UPDATE를 사용하지 않는다.
        CouponStock stock = couponStockRepository.findById(couponId)
                .orElseThrow(() -> couponNotFound(couponId, request));
        if (stock.getRemainingQuantity() <= 0) {
            throw CouponIssueException.soldOut(couponId, request);
        }

        stock.issue();
        saveIssue(couponId, request);
        return CouponIssueResponse.success(couponId, request);
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.DIRECT;
    }

    private void rejectDuplicate(Long couponId, CouponIssueRequest request) {
        if (couponIssueRepository.existsByRequestId(request.requestId())) {
            throw CouponIssueException.duplicateRequest(couponId, request);
        }
        if (couponIssueRepository.existsByCoupon_IdAndUser_Id(couponId, request.userId())) {
            throw CouponIssueException.duplicateUser(couponId, request);
        }
    }

    private CouponIssueException couponNotFound(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.COUPON_NOT_FOUND,
                "Coupon stock was not found");
    }

    private void saveIssue(Long couponId, CouponIssueRequest request) {
        try {
            couponIssueRepository.saveAndFlush(CouponIssue.builder()
                    .coupon(couponRepository.getReferenceById(couponId))
                    .user(userRepository.getReferenceById(request.userId()))
                    .requestId(request.requestId())
                    .build());
        } catch (DataIntegrityViolationException exception) {
            throw CouponIssueException.translateDataIntegrityViolation(
                    couponId,
                    request,
                    exception);
        }
    }
}
