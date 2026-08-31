package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;

class CouponStockTest {

    @Test
    void issueAndResetStock() {
        CouponStock stock = CouponStock.builder()
                .couponId(1L)
                .quantity(100)
                .build();

        stock.issue();

        assertThat(stock.getTotalQuantity()).isEqualTo(100);
        assertThat(stock.getIssuedQuantity()).isEqualTo(1);
        assertThat(stock.getRemainingQuantity()).isEqualTo(99);
        assertThat(stock.getVersion()).isZero();

        stock.reset();

        assertThat(stock.getIssuedQuantity()).isZero();
        assertThat(stock.getRemainingQuantity()).isEqualTo(100);
        assertThat(stock.getVersion()).isZero();
    }

    @Test
    void cannotIssueMoreThanRemainingStock() {
        CouponStock stock = CouponStock.builder()
                .couponId(1L)
                .quantity(1)
                .build();
        stock.issue();

        assertThatThrownBy(stock::issue)
                .isInstanceOf(IllegalStateException.class);
        assertThat(stock.getIssuedQuantity()).isEqualTo(1);
        assertThat(stock.getRemainingQuantity()).isZero();
    }

    @Test
    void versionFieldDoesNotUseJpaVersion() throws NoSuchFieldException {
        assertThat(CouponStock.class.getDeclaredField("version").getAnnotations())
                .noneMatch(annotation -> annotation.annotationType().getName()
                        .equals("jakarta.persistence.Version"));
    }
}
