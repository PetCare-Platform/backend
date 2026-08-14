package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.service.DirectCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class DirectCouponIssueServiceTest {

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DirectCouponIssueServiceImpl service;

    @Test
    void issuesCouponWithoutAcquiringPessimisticLock() {
        CouponIssueRequest request = request(1L, "direct-request-1");
        CouponStock stock = CouponStock.builder()
                .couponId(10L)
                .quantity(2)
                .build();
        when(couponStockRepository.findById(10L)).thenReturn(Optional.of(stock));
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(10L, request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        assertThat(stock.getIssuedQuantity()).isEqualTo(1);
        assertThat(stock.getRemainingQuantity()).isEqualTo(1);
        verify(couponStockRepository, never()).findByIdWithPessimisticLock(any());
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
    }

    @Test
    void rejectsDuplicateRequestBeforeReadingStock() {
        CouponIssueRequest request = request(1L, "same-request");
        when(couponIssueRepository.existsByRequestId(request.requestId())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(10L, request))
                .isInstanceOfSatisfying(
                        GeneralException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ExperimentErrorCode.DUPLICATE_REQUEST));
    
        verifyNoInteractions(couponStockRepository, couponRepository, userRepository);
    }

    private CouponIssueRequest request(Long userId, String requestId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .requestId(requestId)
                .build();
    }
}
