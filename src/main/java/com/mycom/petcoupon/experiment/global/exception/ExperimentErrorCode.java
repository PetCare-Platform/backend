package com.mycom.petcoupon.experiment.global.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ExperimentErrorCode implements BaseErrorCode {

	COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "EXPERIMENT404-0", "쿠폰을 찾을 수 없습니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "EXPERIMENT409-0", "쿠폰 재고가 소진되었습니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "EXPERIMENT409-1", "이미 처리된 요청입니다."),
    DUPLICATE_USER(HttpStatus.CONFLICT, "EXPERIMENT409-2", "이미 쿠폰을 발급받은 사용자입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
