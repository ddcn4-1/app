package org.ddcn41.ticketing_system.global.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.ddcn41.ticketing_system.dto.response.LogoutResponse;
import org.ddcn41.ticketing_system.global.exception.TokenProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TokenProcessingException.class)
    public ResponseEntity<ApiResponse<?>> handleTokenProcessingException(
            TokenProcessingException ex, HttpServletRequest request) {

        // 현재 인증된 사용자 정보 가져오기
        String username = getCurrentUsername(request);

        LogoutResponse errorData = new LogoutResponse(username);

        ApiResponse<LogoutResponse> response = ApiResponse.error(
                "토큰 처리 중 오류 발생",
                ex.getMessage(),
                errorData
        );

        return ResponseEntity.badRequest().body(response);
    }

    private String getCurrentUsername(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "anonymous";
    }

    // 다른 예외들도 필요에 따라 추가
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneralException(Exception ex) {
        ApiResponse<?> response = ApiResponse.error("서버 오류가 발생했습니다.", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
