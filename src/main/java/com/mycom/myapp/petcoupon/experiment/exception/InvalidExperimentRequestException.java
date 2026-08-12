package com.mycom.petcoupon.experiment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidExperimentRequestException extends RuntimeException {

    public InvalidExperimentRequestException(String message) {
        super(message);
    }
}
