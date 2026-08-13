package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.experiment.coupon.service.CouponIssueService;
import com.mycom.petcoupon.experiment.coupon.service.CouponIssueServiceResolver;
import com.mycom.petcoupon.experiment.coupon.type.CouponIssueStrategy;

class CouponIssueServiceResolverTest {

    @Test
    void resolvesServiceByStrategy() {
        CouponIssueService directService = serviceFor(CouponIssueStrategy.DIRECT);
        CouponIssueService pessimisticService = serviceFor(CouponIssueStrategy.PESSIMISTIC);
        CouponIssueServiceResolver resolver = new CouponIssueServiceResolver(
                List.of(directService, pessimisticService));

        assertThat(resolver.resolve(CouponIssueStrategy.DIRECT)).isSameAs(directService);
        assertThat(resolver.resolve(CouponIssueStrategy.PESSIMISTIC)).isSameAs(pessimisticService);
    }

    @Test
    void rejectsUnsupportedStrategy() {
        CouponIssueServiceResolver resolver = new CouponIssueServiceResolver(
                List.of(serviceFor(CouponIssueStrategy.DIRECT)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolve(CouponIssueStrategy.REDIS))
                .withMessageContaining(CouponIssueStrategy.REDIS.name());
    }

    @Test
    void rejectsDuplicateStrategyRegistration() {
        CouponIssueService firstService = serviceFor(CouponIssueStrategy.DIRECT);
        CouponIssueService secondService = serviceFor(CouponIssueStrategy.DIRECT);

        assertThatIllegalStateException()
                .isThrownBy(() -> new CouponIssueServiceResolver(
                        List.of(firstService, secondService)))
                .withMessageContaining(CouponIssueStrategy.DIRECT.name());
    }

    private CouponIssueService serviceFor(CouponIssueStrategy strategy) {
        CouponIssueService service = mock(CouponIssueService.class);
        when(service.supports()).thenReturn(strategy);
        return service;
    }
}
