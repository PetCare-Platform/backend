package com.mycom.petcoupon.experiment.global.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice(
        basePackages = "com.mycom.petcoupon.experiment"
)
public class GlobalExceptionHandler {
	
	// 커스텀 에러 처리
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ErrorResponse<Void>> handleCustomException(GeneralException ex) {
    	
    	BaseErrorCode errorCode = ex.getErrorCode();
    	
    	log.warn(
                "[CustomException]: {}",
                errorCode.getMessage()
        );
    	
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, null));
    }
    

    // @Valid 및 Validation 예외 처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );

        BaseErrorCode validationErrorCode = CommonErrorCode.NOT_VALID_ERROR;
        
        ErrorResponse<Map<String, String>> errorResponse = ErrorResponse.of(validationErrorCode, errors);
        
        return ResponseEntity
                .status(validationErrorCode.getStatus())
                .body(errorResponse);
    }

    // 이외의 모든 예외 처리 
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> handleAllException(Exception ex) {

        log.error("[ WARNING ] Unhandled Exception : {} ", ex.getMessage(), ex);
        
        BaseErrorCode baseErrorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        
        return ResponseEntity
                .status(baseErrorCode.getStatus())
                .body(ErrorResponse.of(baseErrorCode, null));
    }
}
