package org.ddcn41.ticketing_system.domain.queue.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Queue 관련 import
import org.ddcn41.ticketing_system.domain.queue.dto.request.HeartbeatRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.request.SessionReleaseRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.request.QueueCheckRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueCheckResponse;
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;

// User 관련 import
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.service.UserService;

// Response 관련 import
import org.ddcn41.ticketing_system.dto.response.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
@Tag(name = "Queue", description = "대기열 관리 API")
public class QueueController {

    private final QueueService queueService;
    private final UserService userService;

    /**
     * 대기열 필요성 확인 (오버부킹 적용)
     */
    @PostMapping("/check")
    @Operation(summary = "대기열 필요성 확인", description = "예매 시도 시 대기열이 필요한지 확인합니다. (오버부킹 적용)")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<QueueCheckResponse>> checkQueueRequirement(
            @Valid @RequestBody QueueCheckRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        QueueCheckResponse response = queueService.checkQueueRequirement(
                request.getPerformanceId(),
                request.getScheduleId(),
                user.getUserId()
        );

        return ResponseEntity.ok(
                ApiResponse.success("대기열 확인 완료", response)
        );
    }

    /**
     * Heartbeat 전송 (사용자 활성 상태 유지)
     */
    @PostMapping("/heartbeat")
    @Operation(summary = "Heartbeat 전송", description = "사용자가 활성 상태임을 알리는 heartbeat을 전송합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Heartbeat 수신 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패")
    })
    public ResponseEntity<ApiResponse<String>> sendHeartbeat(
            @Valid @RequestBody HeartbeatRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        queueService.updateHeartbeat(
                user.getUserId(),
                request.getPerformanceId(),
                request.getScheduleId()
        );

        return ResponseEntity.ok(
                ApiResponse.success("Heartbeat 수신됨")
        );
    }

    /**
     * 세션 명시적 해제 (페이지 이탈 시)
     */
    @PostMapping("/release-session")
    @Operation(summary = "세션 해제", description = "사용자가 페이지를 떠날 때 세션을 명시적으로 해제합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "세션 해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패")
    })
    public ResponseEntity<ApiResponse<String>> releaseSession(
            @Valid @RequestBody SessionReleaseRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        queueService.releaseSession(
                user.getUserId(),
                request.getPerformanceId(),
                request.getScheduleId()
        );

        return ResponseEntity.ok(
                ApiResponse.success("세션이 해제되었습니다")
        );
    }

    /**
     * 토큰 취소 (대기열에서 나가기)
     */
    @DeleteMapping("/token/{token}")
    @Operation(summary = "토큰 취소", description = "대기열에서 나가고 토큰을 취소합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "취소 성공",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "권한 없음",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "토큰을 찾을 수 없음",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<String>> cancelToken(
            @Parameter(description = "취소할 토큰", required = true)
            @PathVariable String token,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        queueService.cancelToken(token, user.getUserId());

        return ResponseEntity.ok(
                ApiResponse.success("토큰이 취소되었습니다")
        );
    }

    /**
     * 세션 초기화 (테스트용 - 관리자 전용)
     */
    @PostMapping("/clear-sessions")
    @Operation(summary = "세션 초기화", description = "테스트를 위해 모든 활성 세션을 초기화합니다. (관리자 전용)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "세션 초기화 완료",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "관리자 권한 필요",
                    content = @Content)
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> clearAllSessions() {
        queueService.clearAllSessions();
        return ResponseEntity.ok(ApiResponse.success("모든 세션이 초기화되었습니다"));
    }
}