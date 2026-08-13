package com.mycom.petcoupon.experiment.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ErrorResponse<T> {

    private final String code;
    private final String message;
    private final T data;

    public static <T> ErrorResponse<T> of(
            BaseErrorCode errorCode,
            T data
    ) {
        return new ErrorResponse<>(
                errorCode.getCode(),
                errorCode.getMessage(),
                data
        );
    }
}