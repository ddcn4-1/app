package org.ddcn41.ticketing_system.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;

@Controller
@Tag(name = "Page Controller", description = "HTML 페이지 제공 API")
public class PageController {

    /**
     * 관리자 로그인 페이지
     */
    @Operation(summary = "관리자 로그인 페이지", description = "관리자 로그인 HTML 페이지를 반환합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "HTML 페이지 반환 성공",
                    content = @Content(mediaType = "text/html")),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping(value = "/admin/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> adminLoginPage() throws IOException {
        Resource resource = new ClassPathResource("static/admin-login.html");
        byte[] content = Files.readAllBytes(resource.getFile().toPath());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(content);
    }

    /**
     * 관리자 대시보드 페이지
     */
    @Operation(summary = "관리자 대시보드 페이지", description = "관리자 대시보드 HTML 페이지를 반환합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "HTML 페이지 반환 성공",
                    content = @Content(mediaType = "text/html")),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping(value = "/admin/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> adminDashboardPage() throws IOException {
        Resource resource = new ClassPathResource("static/admin.html");
        byte[] content = Files.readAllBytes(resource.getFile().toPath());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(content);
    }

    /**
     * 일반 로그인 페이지
     */
    @Operation(summary = "일반 로그인 페이지", description = "일반 사용자 로그인 HTML 페이지를 반환합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "HTML 페이지 반환 성공",
                    content = @Content(mediaType = "text/html")),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> loginPage() throws IOException {
        Resource resource = new ClassPathResource("static/login.html");
        byte[] content = Files.readAllBytes(resource.getFile().toPath());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(content);
    }
}
