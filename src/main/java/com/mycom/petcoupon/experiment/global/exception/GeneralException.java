package com.mycom.petcoupon.experiment.global.exception;

import lombok.Getter;

@Getter
public class GeneralException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final BaseErrorCode errorCode;

    public GeneralException(BaseErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}