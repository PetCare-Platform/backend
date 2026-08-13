package com.mycom.petcoupon.experiment.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Builder;

@Builder
public record CouponIssueRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 64) String requestId
) {
}
