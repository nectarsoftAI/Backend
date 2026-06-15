package com.nectarsoft.meetai.core.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class MeetAiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public MeetAiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public MeetAiException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}
