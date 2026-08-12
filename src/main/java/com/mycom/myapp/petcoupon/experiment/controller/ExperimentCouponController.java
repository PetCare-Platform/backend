package com.mycom.petcoupon.experiment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.experiment.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.dto.CreateCouponResponse;
import com.mycom.petcoupon.experiment.dto.IssueRequest;
import com.mycom.petcoupon.experiment.dto.IssueResponse;
import com.mycom.petcoupon.experiment.exception.InvalidExperimentRequestException;
import com.mycom.petcoupon.experiment.service.ConditionalUpdateService;
import com.mycom.petcoupon.experiment.service.DirectService;
import com.mycom.petcoupon.experiment.service.ExperimentCouponService;
import com.mycom.petcoupon.experiment.service.OptimisticLockService;
import com.mycom.petcoupon.experiment.service.PessimisticLockService;
import com.mycom.petcoupon.experiment.service.RedisDecrService;

@RestController
@RequestMapping("/experiments")
public class ExperimentCouponController {

    private final ExperimentCouponService experimentCouponService;
    private final DirectService directService;
    private final PessimisticLockService pessimisticLockService;
    private final OptimisticLockService optimisticLockService;
    private final ConditionalUpdateService conditionalUpdateService;
    private final RedisDecrService redisDecrService;

    public ExperimentCouponController(
            ExperimentCouponService experimentCouponService,
            DirectService directService,
            PessimisticLockService pessimisticLockService,
            OptimisticLockService optimisticLockService,
            ConditionalUpdateService conditionalUpdateService,
            RedisDecrService redisDecrService) {
        this.experimentCouponService = experimentCouponService;
        this.directService = directService;
        this.pessimisticLockService = pessimisticLockService;
        this.optimisticLockService = optimisticLockService;
        this.conditionalUpdateService = conditionalUpdateService;
        this.redisDecrService = redisDecrService;
    }

    @PostMapping("/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateCouponResponse createCoupon(@RequestBody CreateCouponRequest request) {
        return experimentCouponService.create(request);
    }

    @PostMapping("/direct/coupons/{couponId}/issue")
    public IssueResponse issueDirect(
            @PathVariable("couponId") Long couponId,
            @RequestBody IssueRequest request) {
        Long userId = userId(request);
        directService.issue(couponId, userId);
        return issued(couponId, userId, "DIRECT_JPA");
    }

    @PostMapping("/pessimistic/coupons/{couponId}/issue")
    public IssueResponse issuePessimistic(
            @PathVariable("couponId") Long couponId,
            @RequestBody IssueRequest request) {
        Long userId = userId(request);
        pessimisticLockService.issue(couponId, userId);
        return issued(couponId, userId, "PESSIMISTIC_LOCK");
    }

    @PostMapping("/optimistic/coupons/{couponId}/issue")
    public IssueResponse issueOptimistic(
            @PathVariable("couponId") Long couponId,
            @RequestBody IssueRequest request) {
        Long userId = userId(request);
        optimisticLockService.issue(couponId, userId);
        return issued(couponId, userId, "OPTIMISTIC_CAS");
    }

    @PostMapping("/conditional/coupons/{couponId}/issue")
    public IssueResponse issueConditional(
            @PathVariable("couponId") Long couponId,
            @RequestBody IssueRequest request) {
        Long userId = userId(request);
        conditionalUpdateService.issue(couponId, userId);
        return issued(couponId, userId, "CONDITIONAL_UPDATE");
    }

    @PostMapping("/redis-decr/coupons/{couponId}/issue")
    public IssueResponse issueRedisDecr(
            @PathVariable("couponId") Long couponId,
            @RequestBody IssueRequest request) {
        Long userId = userId(request);
        redisDecrService.issue(couponId, userId);
        return issued(couponId, userId, "REDIS_DECR");
    }

    @PostMapping("/redis-decr/coupons/{couponId}/initialize")
    public CouponStatusResponse initializeRedis(
            @PathVariable("couponId") Long couponId) {
        redisDecrService.initialize(couponId);
        return redisDecrService.getStatus(couponId);
    }

    @GetMapping("/coupons/{couponId}/status")
    public CouponStatusResponse getStatus(
            @PathVariable("couponId") Long couponId) {
        return experimentCouponService.getStatus(couponId);
    }

    @GetMapping("/redis-decr/coupons/{couponId}/status")
    public CouponStatusResponse getRedisStatus(
            @PathVariable("couponId") Long couponId) {
        return redisDecrService.getStatus(couponId);
    }

    @PostMapping("/coupons/{couponId}/reset")
    public CouponStatusResponse reset(
            @PathVariable("couponId") Long couponId) {
        return experimentCouponService.reset(couponId);
    }

    private Long userId(IssueRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) {
            throw new InvalidExperimentRequestException("userId는 1 이상의 값이어야 합니다.");
        }
        return request.userId();
    }

    private IssueResponse issued(Long couponId, Long userId, String strategy) {
        return new IssueResponse(couponId, userId, strategy, true);
    }
}
