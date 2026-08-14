package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.mycom.petcoupon.experiment.coupon.service.OptimisticCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.CommonErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class OptimisticCouponIssueServiceTest {

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OptimisticCouponIssueServiceImpl service;

    @Test
    void issuesCouponWhenVersionMatches() {
        CouponIssueRequest request = request(1L, "optimistic-request-1");
        CouponStock stock = stock(10L, 2);
        when(couponStockRepository.findById(10L)).thenReturn(Optional.of(stock));
        when(couponStockRepository.issueIfVersionMatches(10L, stock.getVersion())).thenReturn(1);
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(10L, request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        verify(couponStockRepository).issueIfVersionMatches(10L, 0L);
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
    }

    @Test
    void retriesAfterVersionConflict() {
        CouponIssueRequest request = request(1L, "optimistic-request-2");
        CouponStock stock = stock(10L, 2);
        when(couponStockRepository.findById(10L)).thenReturn(Optional.of(stock));
        when(couponStockRepository.issueIfVersionMatches(10L, stock.getVersion()))
                .thenReturn(0, 1);
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(10L, request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        verify(couponStockRepository, times(2)).findById(10L);
        verify(couponStockRepository, times(2)).issueIfVersionMatches(10L, 0L);
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
    }

    @Test
    void failsAfterRetryLimitIsExhausted() {
        CouponIssueRequest request = request(1L, "optimistic-request-3");
        CouponStock stock = stock(10L, 2);
        when(couponStockRepository.findById(10L)).thenReturn(Optional.of(stock));
        when(couponStockRepository.issueIfVersionMatches(10L, stock.getVersion())).thenReturn(0);

        assertThatThrownBy(() -> service.issue(10L, request))
                .isInstanceOfSatisfying(
                        GeneralException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        verify(couponStockRepository, times(10)).findById(10L);
        verify(couponStockRepository, times(10)).issueIfVersionMatches(10L, 0L);
    }

    private CouponIssueRequest request(Long userId, String requestId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .requestId(requestId)
                .build();
    }

    private CouponStock stock(Long couponId, int quantity) {
        return CouponStock.builder()
                .couponId(couponId)
                .quantity(quantity)
                .build();
    }
}
