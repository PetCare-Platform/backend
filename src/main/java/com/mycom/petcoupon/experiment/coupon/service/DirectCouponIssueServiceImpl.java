package com.mycom.petcoupon.experiment.coupon.service;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;
import com.mycom.petcoupon.experiment.global.exception.CommonErrorCode;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
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
                .orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));
        if (stock.getRemainingQuantity() <= 0) {
            throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
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
        	throw new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
        }
        if (couponIssueRepository.existsByCoupon_IdAndUser_Id(couponId, request.userId())) {
        	throw new GeneralException(ExperimentErrorCode.DUPLICATE_USER);
        }
    }

    private void saveIssue(Long couponId, CouponIssueRequest request) {
        try {
            couponIssueRepository.saveAndFlush(CouponIssue.builder()
                    .coupon(couponRepository.getReferenceById(couponId))
                    .user(userRepository.getReferenceById(request.userId()))
                    .requestId(request.requestId())
                    .build());
        } catch (DataIntegrityViolationException exception) {
        	throw translateDataIntegrityViolation(exception);
        }
    }
    
    // DB UNIQUE 제약조건 오류를 현재 예외 구조로 변환
    private GeneralException translateDataIntegrityViolation(
            DataIntegrityViolationException exception
    ) {
        String message = getRootCauseMessage(exception)
                .toLowerCase(Locale.ROOT);

        if (message.contains("uq_request_id")) {
            return new GeneralException(
                    ExperimentErrorCode.DUPLICATE_REQUEST
            );
        }

        if (message.contains("uq_coupon_user")) {
            return new GeneralException(
                    ExperimentErrorCode.DUPLICATE_USER
            );
        }

        return new GeneralException(
                CommonErrorCode.INTERNAL_SERVER_ERROR
        );
    }

    // DataIntegrityViolationException의 실제 DB 오류 메시지 추출
    private String getRootCauseMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null
                ? ""
                : current.getMessage();
    }
}
