package com.mycom.petcoupon.experiment.global.exception;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;

import lombok.Getter;

@Getter
public class CouponIssueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long couponId;
    private final Long userId;
    private final String requestId;
    private final CouponIssueResult result;

    public CouponIssueException(
            Long couponId,
            Long userId,
            String requestId,
            CouponIssueResult result,
            String message) {
        super(message);
        this.couponId = couponId;
        this.userId = userId;
        this.requestId = requestId;
        this.result = result;
    }

    public CouponIssueException(
            Long couponId,
            Long userId,
            String requestId,
            CouponIssueResult result,
            String message,
            Throwable cause) {
        super(message, cause);
        this.couponId = couponId;
        this.userId = userId;
        this.requestId = requestId;
        this.result = result;
    }

    public static CouponIssueException soldOut(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.SOLD_OUT,
                "Coupon stock is exhausted");
    }

    public static CouponIssueException duplicateRequest(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.DUPLICATE_REQUEST,
                "requestId has already been processed");
    }

    public static CouponIssueException duplicateUser(
            Long couponId,
            CouponIssueRequest request) {
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.DUPLICATE_USER,
                "The user has already received this coupon");
    }

    public static RuntimeException translateDataIntegrityViolation(
            Long couponId,
            CouponIssueRequest request,
            DataIntegrityViolationException exception) {
        String message = rootCauseMessage(exception).toLowerCase(Locale.ROOT);
        if (message.contains("uq_request_id")) {
            return new CouponIssueException(
                    couponId,
                    request.userId(),
                    request.requestId(),
                    CouponIssueResult.DUPLICATE_REQUEST,
                    "requestId has already been processed",
                    exception);
        }
        if (message.contains("uq_coupon_user")) {
            return new CouponIssueException(
                    couponId,
                    request.userId(),
                    request.requestId(),
                    CouponIssueResult.DUPLICATE_USER,
                    "The user has already received this coupon",
                    exception);
        }
        return new CouponIssueException(
                couponId,
                request.userId(),
                request.requestId(),
                CouponIssueResult.INTERNAL_ERROR,
                "Database integrity violation",
                exception);
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }
}
