package com.mycom.petcoupon.experiment.global.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400-0", "잘못된 요청입니다."),
    NOT_VALID_ERROR(HttpStatus.BAD_REQUEST, "COMMON400-1", "입력값이 유효하지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404", "리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}