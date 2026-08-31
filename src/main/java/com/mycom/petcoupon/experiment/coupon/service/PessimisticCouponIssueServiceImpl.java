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

/**
 * 비관적 락(Pessimistic Lock) 전략.
 *
 * SELECT 시점에 곧바로 row를 잠근다(SELECT ... FOR UPDATE). 같은 쿠폰에 대한
 * 다른 트랜잭션은 이 트랜잭션이 커밋/롤백될 때까지 대기하므로, 별도의 버전 비교나
 * 재시도 로직 없이 그냥 순차적으로 처리하는 것처럼 재고를 안전하게 차감할 수 있다.
 * 대신 락을 오래 들고 있으면 동시 요청이 많을수록 대기 시간이 늘어난다(OptimisticCouponIssueServiceImpl과 비교 포인트).
 */
@Service
@RequiredArgsConstructor
public class PessimisticCouponIssueServiceImpl implements CouponIssueService {

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    @Transactional
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        rejectDuplicate(couponId, request);

        // findByIdWithPessimisticLock()이 SELECT ... FOR UPDATE를 실행하는 순간 락을 획득한다.
        // 이후 stock.issue()로 메모리상 값을 바꾸면, 트랜잭션 커밋 시 JPA dirty checking으로 UPDATE가 나간다.
        CouponStock stock = couponStockRepository.findByIdWithPessimisticLock(couponId)
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
        return CouponIssueStrategy.PESSIMISTIC;
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
        	// stock 락 덕분에 재고 차감 자체는 안전하지만, coupon_issue의 unique 제약(uq_request_id / uq_coupon_user)은
        	// stock 락과 무관하게 걸릴 수 있어 rejectDuplicate()의 사전 체크만으로는 완전히 막지 못한다.
        	// 그 마지막 방어선으로 여기서 제약조건 이름을 보고 도메인 예외로 변환한다.
        	throw translateDataIntegrityViolation(exception);
        }
    }
    
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
