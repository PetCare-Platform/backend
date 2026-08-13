package com.mycom.petcoupon.experiment.coupon.dto;

import lombok.Builder;

@Builder
public record CreateCouponRequest(Integer quantity) {
}
