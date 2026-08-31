package com.mycom.petcoupon.experiment.global.exception;

import org.springframework.http.HttpStatus;

public interface BaseErrorCode {
	HttpStatus getStatus();

    String getCode();

    String getMessage();
}
