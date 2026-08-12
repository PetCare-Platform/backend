package com.mycom.petcoupon.experiment.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponResponse;
import com.mycom.petcoupon.experiment.coupon.service.DirectCouponIssueService;
import com.mycom.petcoupon.experiment.coupon.service.ExperimentCouponService;
import com.mycom.petcoupon.experiment.coupon.service.PessimisticCouponIssueService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/experiment")
@RequiredArgsConstructor
public class ExperimentCouponController {

    private final ExperimentCouponService experimentCouponService;
    private final DirectCouponIssueService directCouponIssueService;
    private final PessimisticCouponIssueService pessimisticCouponIssueService;

    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateCouponResponse createCoupon(@RequestBody CreateCouponRequest request) {
        return experimentCouponService.create(request);
    }

    @PostMapping("/coupons/{couponId}/direct")
    public CouponIssueResponse issueDirect(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return directCouponIssueService.issue(couponId, request);
    }

    @PostMapping("/coupons/{couponId}/pessimistic")
    public CouponIssueResponse issuePessimistic(
            @PathVariable("couponId") Long couponId,
            @Valid @RequestBody CouponIssueRequest request) {
        return pessimisticCouponIssueService.issue(couponId, request);
    }

    @GetMapping("/coupons/{couponId}/status")
    public CouponStatusResponse getStatus(@PathVariable("couponId") Long couponId) {
        return experimentCouponService.getStatus(couponId);
    }

    @PostMapping("/coupons/{couponId}/reset")
    public CouponStatusResponse reset(@PathVariable("couponId") Long couponId) {
        return experimentCouponService.reset(couponId);
    }
}
