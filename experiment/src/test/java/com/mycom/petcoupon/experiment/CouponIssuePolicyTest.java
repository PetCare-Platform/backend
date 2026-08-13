package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.service.PessimisticCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

import jakarta.persistence.LockModeType;

@ExtendWith(MockitoExtension.class)
class CouponIssuePolicyTest {

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PessimisticCouponIssueServiceImpl service;

    @Test
    void duplicateRequestIsCheckedBeforeStockLock() {
        CouponIssueRequest request = request(1L, "same-request");
        when(couponIssueRepository.existsByRequestId(request.requestId())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(1L, request))
                .isInstanceOfSatisfying(GeneralException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ExperimentErrorCode.DUPLICATE_REQUEST));

        verifyNoInteractions(couponStockRepository, couponRepository, userRepository);
    }

    @Test
    void duplicateUserHasASeparateResult() {
        CouponIssueRequest request = request(1L, "new-request");
        when(couponIssueRepository.existsByRequestId(request.requestId())).thenReturn(false);
        when(couponIssueRepository.existsByCoupon_IdAndUser_Id(1L, request.userId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.issue(1L, request))
                .isInstanceOfSatisfying(GeneralException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ExperimentErrorCode.DUPLICATE_USER));

        verifyNoInteractions(couponStockRepository, couponRepository, userRepository);
    }

    @Test
    void successfulIssueChangesStockAndSavesIssue() {
        CouponIssueRequest request = request(1L, "request-1");
        CouponStock stock = CouponStock.builder()
                .couponId(10L)
                .quantity(2)
                .build();
        when(couponStockRepository.findByIdWithPessimisticLock(10L))
                .thenReturn(Optional.of(stock));
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(10L, request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        assertThat(stock.getIssuedQuantity()).isEqualTo(1);
        assertThat(stock.getRemainingQuantity()).isEqualTo(1);
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
    }

    @Test
    void soldOutDoesNotInsertIssue() {
        CouponIssueRequest request = request(1L, "request-1");
        CouponStock stock = CouponStock.builder()
                .couponId(10L)
                .quantity(1)
                .build();
        stock.issue();
        when(couponStockRepository.findByIdWithPessimisticLock(10L))
                .thenReturn(Optional.of(stock));

        assertThatThrownBy(() -> service.issue(10L, request))
                .isInstanceOfSatisfying(GeneralException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ExperimentErrorCode.SOLD_OUT));

        verify(couponIssueRepository, never()).saveAndFlush(any());
        verifyNoInteractions(couponRepository, userRepository);
    }

    @Test
    void stockQueryUsesPessimisticWriteLock() throws NoSuchMethodException {
        Method method = CouponStockRepository.class.getMethod(
                "findByIdWithPessimisticLock",
                Long.class);

        assertThat(method.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(method.getAnnotation(Query.class).value())
                .contains("stock.couponId = :couponId");
    }

    private CouponIssueRequest request(Long userId, String requestId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .requestId(requestId)
                .build();
    }
}
