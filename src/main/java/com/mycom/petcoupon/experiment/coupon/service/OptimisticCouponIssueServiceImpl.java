package com.mycom.petcoupon.experiment.coupon.service;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
 * 낙관적 락(Optimistic Lock) 전략.
 *
 * PessimisticCouponIssueServiceImpl과 달리 SELECT 시점에 row를 잠그지 않는다.
 * 대신 조회한 version 값을 그대로 WHERE 조건에 걸어 UPDATE하고(compare-and-swap),
 * 그 사이 다른 트랜잭션이 먼저 값을 바꿔서 UPDATE된 row가 0건이면 "충돌"로 보고 재시도한다.
 * CouponStock은 여러 전략이 공유하는 엔티티라 JPA의 @Version 자동 관리 기능은
 * 의도적으로 쓰지 않고(CouponStockTest에서 강제), 버전 비교/증가를 직접 쿼리로 구현한다.
 */
@Service
@RequiredArgsConstructor
public class OptimisticCouponIssueServiceImpl implements CouponIssueService {

    // REPEATABLE READ(MySQL 기본 격리수준)에서는 트랜잭션 시작 시점에 스냅샷이 고정되기 때문에,
    // 같은 트랜잭션 안에서 재시도하며 findById()를 반복 호출해도 계속 옛날 version만 읽게 되어
    // "충돌 -> 재조회 -> 또 충돌"이 영원히 반복될 수 있다(Codex 리뷰로 발견).
    // READ_COMMITTED로 낮추면 매 SELECT마다 그 시점의 최신 커밋 데이터를 읽으므로 이 문제가 없어진다.
    private static final int MAX_RETRY = 10;

    private final CouponStockRepository couponStockRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Override
    public CouponIssueResponse issue(Long couponId, CouponIssueRequest request) {
        rejectDuplicate(couponId, request);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            CouponStock stock = couponStockRepository.findById(couponId)
                    .orElseThrow(() -> new GeneralException(ExperimentErrorCode.COUPON_NOT_FOUND));
            if (stock.getRemainingQuantity() <= 0) {
                throw new GeneralException(ExperimentErrorCode.SOLD_OUT);
            }

            // 조회한 version과 DB의 현재 version이 같을 때만 UPDATE가 성공한다(1건).
            // 그 사이 다른 트랜잭션이 먼저 커밋해서 version이 바뀌었다면 0건 -> 최신 상태로 재시도.
            int updated = couponStockRepository.issueIfVersionMatches(couponId, stock.getVersion());
            if (updated == 1) {
                saveIssue(couponId, request);
                return CouponIssueResponse.success(couponId, request);
            }
        }

        // 극단적인 동시 경쟁 상황에서 재시도 횟수를 다 소진한 경우의 안전장치.
        throw new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Override
    public CouponIssueStrategy supports() {
        return CouponIssueStrategy.OPTIMISTIC;
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
            // rejectDuplicate()의 사전 체크는 동시에 들어온 두 요청을 둘 다 통과시킬 수 있다.
            // 이후 DB unique 제약(uq_request_id / uq_coupon_user)에서 마지막 방어선으로 걸러낸다.
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
