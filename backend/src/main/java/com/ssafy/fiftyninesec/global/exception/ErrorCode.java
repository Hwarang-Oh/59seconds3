package com.ssafy.fiftyninesec.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 이벤트입니다."),

    INVALID_CODE(HttpStatus.UNAUTHORIZED, "잘못된 암호입니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),

    OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "카카오 토큰을 얻을 수 없습니다."),

    REDIS_CANNOT_SAVE(HttpStatus.INTERNAL_SERVER_ERROR, "레디스에 저장할 수 없습니다."),

    KAKAOSUB_NOT_FOUND(HttpStatus.NOT_FOUND, "kakaoSub가 없습니다."),
    TOKEN_CANNOT_CREATE(HttpStatus.BAD_REQUEST, "토큰을 만들 수 없습니다.")
    ;

    private final HttpStatus httpStatus;
    private final String message;
}
