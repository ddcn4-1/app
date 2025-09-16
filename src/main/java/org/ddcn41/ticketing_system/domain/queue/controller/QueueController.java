package org.ddcn41.ticketing_system.domain.queue.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueStatusResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.request.TokenIssueRequest;
import org.ddcn41.ticketing_system.domain.queue.dto.response.TokenIssueResponse;
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.service.UserService;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.springframework.http.ResponseEntity;
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
     * 대기열 토큰 발급
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
}