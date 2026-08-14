package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.service.ConditionalCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ConditionalCouponIssueServiceTest {

    @Mock
    private CouponStockRepository couponStockRepository;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ConditionalCouponIssueServiceImpl service;

    @Test
    void issuesCouponWhenConditionalUpdateSucceeds() {
        CouponIssueRequest request = request(1L, "conditional-request-1");
        when(couponStockRepository.issueIfStockAvailable(10L)).thenReturn(1);
        when(couponRepository.getReferenceById(10L)).thenReturn(mock(Coupon.class));
        when(userRepository.getReferenceById(1L)).thenReturn(mock(User.class));

        CouponIssueResponse response = service.issue(10L, request);

        assertThat(response.result()).isEqualTo(CouponIssueResult.SUCCESS);
        verify(couponStockRepository).issueIfStockAvailable(10L);
        verify(couponIssueRepository).saveAndFlush(any(CouponIssue.class));
    }

    @Test
    void reportsSoldOutWhenCouponExistsAndUpdateFails() {
        CouponIssueRequest request = request(1L, "conditional-request-2");
        when(couponStockRepository.issueIfStockAvailable(10L)).thenReturn(0);
        when(couponStockRepository.existsById(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.issue(10L, request))
                .isInstanceOfSatisfying(
                        GeneralException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ExperimentErrorCode.SOLD_OUT));

        verify(couponIssueRepository, never()).saveAndFlush(any());
        verifyNoInteractions(couponRepository, userRepository);
    }

    @Test
    void reportsCouponNotFoundWhenCouponDoesNotExist() {
        CouponIssueRequest request = request(1L, "conditional-request-3");
        when(couponStockRepository.issueIfStockAvailable(10L)).thenReturn(0);
        when(couponStockRepository.existsById(10L)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(10L, request))
                .isInstanceOfSatisfying(
                        GeneralException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ExperimentErrorCode.COUPON_NOT_FOUND));

        verify(couponIssueRepository, never()).saveAndFlush(any());
        verifyNoInteractions(couponRepository, userRepository);
    }

    private CouponIssueRequest request(Long userId, String requestId) {
        return CouponIssueRequest.builder()
                .userId(userId)
                .requestId(requestId)
                .build();
    }
}
