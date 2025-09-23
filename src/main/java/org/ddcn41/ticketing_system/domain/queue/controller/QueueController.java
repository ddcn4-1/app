package org.ddcn41.ticketing_system.domain.queue.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// Queue 관련 import
import org.ddcn41.ticketing_system.domain.queue.dto.request.HeartbeatRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.request.TokenActivateRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.request.TokenIssueRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.request.TokenRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueCheckResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueStatusResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.response.TokenIssueResponse;
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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
@Tag(name = "Queue", description = "대기열 관리 API")
public class QueueController {

    private final QueueService queueService;
    private final UserService userService;


    /**
     * 대기열 필요성 확인
     */
    @PostMapping("/check")
    @Operation(summary = "대기열 필요성 확인", description = "예매 시도 시 대기열이 필요한지 확인합니다. (오버부킹 적용)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "대기열 확인 완료",
                    content = @Content(schema = @Schema(implementation = QueueCheckResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "공연을 찾을 수 없음",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<QueueCheckResponse>> checkQueueRequirement(
            @Valid @RequestBody TokenRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        QueueCheckResponse response = queueService.getBookingToken(
                request.getPerformanceId(),
                request.getScheduleId(),
                user.getUserId()
        );

        return ResponseEntity.ok(
                ApiResponse.success("대기열 확인 완료", response)
        );
    }

    /**
     * 대기열 토큰 발급 todo. test 완료 후 삭제
     */
    @PostMapping("/token")
    @Operation(summary = "대기열 토큰 발급", description = "특정 공연에 대한 대기열 토큰을 발급받습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "토큰 발급 성공",
                    content = @Content(schema = @Schema(implementation = TokenIssueResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "공연을 찾을 수 없음",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<TokenIssueResponse>> issueToken(
            @Valid @RequestBody TokenIssueRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        TokenIssueResponse response = queueService.issueQueueToken(
                user.getUserId(), request.getPerformanceId());

        return ResponseEntity.ok(
                ApiResponse.success("대기열 토큰이 발급되었습니다", response)
        );
    }

    /**
     * 대기열 토큰 활성화 (입장 승인)
     */
    @PostMapping("/activate")
    @Operation(summary = "대기열 토큰 활성화", description = "WAITING 상태의 토큰을 ACTIVE로 승격합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "활성화 성공",
                    content = @Content(schema = @Schema(implementation = QueueStatusResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "토큰을 찾을 수 없음",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "아직 활성화 자격 없음",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "410",
                    description = "만료/취소된 토큰",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<QueueStatusResponse>> activateToken(
            @Valid @RequestBody TokenActivateRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        QueueStatusResponse response = queueService.activateToken(
                request.getToken(),
                user.getUserId(),
                request.getPerformanceId(),
                request.getScheduleId()
        );

        return ResponseEntity.ok(
                ApiResponse.success("대기열 토큰이 활성화되었습니다", response)
        );
    }

    /**
     * 토큰 상태 조회
     */
    @GetMapping("/status/{token}")
    @Operation(summary = "토큰 상태 조회", description = "발급받은 토큰의 현재 상태와 대기열 정보를 조회합니다.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = QueueStatusResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "토큰을 찾을 수 없음",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<QueueStatusResponse>> getTokenStatus(
            @Parameter(description = "토큰 문자열", required = true)
            @PathVariable String token) {

        QueueStatusResponse response = queueService.getTokenStatus(token);

        return ResponseEntity.ok(
                ApiResponse.success("토큰 상태 조회 성공", response)
        );
    }

    /**
     * 사용자의 활성 토큰 목록 조회
     */
    @GetMapping("/my-tokens")
    @Operation(summary = "내 토큰 목록", description = "현재 사용자의 모든 활성 토큰을 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = QueueStatusResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 실패",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<List<QueueStatusResponse>>> getMyTokens(
            Authentication authentication) {

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        List<QueueStatusResponse> responses = queueService.getUserActiveTokens(user.getUserId());

        return ResponseEntity.ok(
                ApiResponse.success("토큰 목록 조회 성공", responses)
        );
    }

    /**
     * Heartbeat 전송 (사용자 활성 상태 유지)
     * Content-Type을 확인하여 JSON과 Beacon 요청을 모두 처리
     */
    @PostMapping("/heartbeat")
    @Operation(summary = "Heartbeat 전송", description = "사용자가 활성 상태임을 알리는 heartbeat를 전송합니다.")
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
            @RequestBody(required = false) HeartbeatRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        try {
            String username = authentication.getName();
            User user = userService.findByUsername(username);

            // Content-Type 확인
            String contentType = httpRequest.getContentType();

            if (request != null) {
                queueService.updateHeartbeat(
                        user.getUserId(),
                        request.getPerformanceId(),
                        request.getScheduleId()
                );
            }

            return ResponseEntity.ok(
                    ApiResponse.success("Heartbeat 수신됨")
            );
        } catch (Exception e) {
            return ResponseEntity.ok(
                    ApiResponse.success("Heartbeat 처리됨") // 에러가 나도 200 반환 (heartbeat 특성상)
            );
        }
    }

    /**
     * Beacon API용 세션 해제 (인증 불필요)
     * 페이지 언로드 시 Beacon으로만 호출되는 엔드포인트
     */
//    @CrossOrigin(origins = "*") // 모든 origin 허용
    @PostMapping(
            value = "/release-session",
            consumes = {"application/json", "text/plain", "*/*"}  // 모든 Content-Type 허용
    )
    @Operation(summary = "Beacon 세션 해제", description = "Beacon API를 통한 세션 해제 (인증 불필요)")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "세션 해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청")
    })
    public ResponseEntity<ApiResponse<String>> releaseSessionBeacon(
            @RequestBody(required = false) String requestBody,
            HttpServletRequest httpRequest) {

        System.out.println("=== releaseSessionBeacon 호출됨 ===");
        System.out.println("Request Body: " + requestBody);

        try {
            if (requestBody != null && !requestBody.isEmpty()) {
                // JSON 파싱
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> request = mapper.readValue(requestBody, Map.class);

                Object performanceIdObj = request.get("performanceId");
                Object scheduleIdObj = request.get("scheduleId");
                Object userIdObj = request.get("userId");

                if (performanceIdObj != null && scheduleIdObj != null && userIdObj != null) {
                    Long performanceId = Long.valueOf(performanceIdObj.toString());
                    Long scheduleId = Long.valueOf(scheduleIdObj.toString());
                    Long userId = Long.valueOf(userIdObj.toString());

                    queueService.releaseSession(userId, performanceId, scheduleId);
                }
            }

            return ResponseEntity.ok(ApiResponse.success("Beacon 세션 해제 처리됨"));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success("Beacon 세션 해제 시도됨"));
        }
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

    /**
     * 세션 상태 확인 (디버그용)
     */
    @GetMapping("/session-info")
    @Operation(summary = "세션 정보 조회", description = "현재 사용자의 세션 정보를 조회합니다.")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<String>> getSessionInfo(
            Authentication authentication,
            HttpServletRequest request) {

        String username = authentication.getName();
        String sessionId = request.getSession().getId();
        String remoteAddr = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");

        String sessionInfo = String.format(
                "Username: %s, SessionId: %s, IP: %s, UserAgent: %s",
                username, sessionId, remoteAddr, userAgent
        );

        return ResponseEntity.ok(
                ApiResponse.success("세션 정보 조회 완료", sessionInfo)
        );
    }
    // QueueController.java에 추가할 메서드



}