package com.mycom.petcoupon.experiment.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidExperimentRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidExperimentRequestException(String message) {
        super(message);
    }
}
