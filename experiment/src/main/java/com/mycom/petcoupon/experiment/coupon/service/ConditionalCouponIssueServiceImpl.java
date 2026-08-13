package com.mycom.petcoupon.experiment.coupon.service;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
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

/**
 * 조건부 UPDATE(Conditional Update) 전략.
 *
 * 비관적 락처럼 미리 잠그지도, 낙관적 락처럼 버전을 비교하지도 않는다.
 * 대신 "재고가 남아있는가"라는 비즈니스 조건 자체를 UPDATE의 WHERE절에 걸어서
 * 조회와 차감을 한 문장으로 원자적으로 처리한다. 조건이 실패하면(0건) 그 시점에
 * 정말 재고가 없다는 뜻이므로, 낙관적 락과 달리 재시도할 필요가 없다.
 */
@Service
@RequiredArgsConstructor
public class ConditionalCouponIssueServiceImpl implements CouponIssueService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    @Transactional
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        rejectDuplicate(couponId, request);

        int updated = couponStockRepository.issueIfStockAvailable(couponId);
        if (updated == 0) {
            if (!couponStockRepository.existsById(couponId)) {
                throw new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND);
            }
            throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
        }

        saveIssue(couponId, request);
        return CouponIssueResponse.success(couponId, request);
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.CONDITIONAL;
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

    private GeneralException translateDataIntegrityViolation(DataIntegrityViolationException exception) {
        String message = rootCauseMessage(exception).toLowerCase(Locale.ROOT);
        if (message.contains("uq_request_id")) {
            return new GeneralException(ExperimentErrorCode.DUPLICATE_REQUEST);
        }
        if (message.contains("uq_coupon_user")) {
            return new GeneralException(ExperimentErrorCode.DUPLICATE_USER);
        }
        return new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }
}
