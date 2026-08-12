package com.mycom.petcoupon.experiment.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;

@RestControllerAdvice
public class ExperimentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExperimentExceptionHandler.class);

    @ExceptionHandler(CouponIssueException.class)
    public ResponseEntity<CouponIssueResponse> handleCouponIssue(
            CouponIssueException exception) {
        return response(
                statusFor(exception.getResult()),
                exception.getCouponId(),
                exception.getUserId(),
                exception.getRequestId(),
                exception.getResult());
    }

    @ExceptionHandler(CouponNotFoundException.class)
    public ResponseEntity<CouponIssueResponse> handleNotFound(
            CouponNotFoundException exception) {
        return response(
                HttpStatus.NOT_FOUND,
                exception.getCouponId(),
                null,
                null,
                CouponIssueResult.COUPON_NOT_FOUND);
    }

    @ExceptionHandler({
            InvalidExperimentRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<CouponIssueResponse> handleInvalidRequest(Exception exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                null,
                null,
                null,
                CouponIssueResult.INVALID_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CouponIssueResponse> handleUnhandledIntegrityViolation(
            DataIntegrityViolationException exception) {
        log.error("Unhandled database integrity violation", exception);
        return internalError();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CouponIssueResponse> handleInternalError(Exception exception) {
        log.error("Unhandled experiment request failure", exception);
        return internalError();
    }

    private HttpStatus statusFor(CouponIssueResult result) {
        return switch (result) {
            case COUPON_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
    }

    private ResponseEntity<CouponIssueResponse> internalError() {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                null,
                null,
                null,
                CouponIssueResult.INTERNAL_ERROR);
    }

    private ResponseEntity<CouponIssueResponse> response(
            HttpStatus status,
            Long couponId,
            Long userId,
            String requestId,
            CouponIssueResult result) {
        return ResponseEntity.status(status)
                .body(CouponIssueResponse.failure(
                        couponId,
                        userId,
                        requestId,
                        result));
    }
}
