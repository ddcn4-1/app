package org.ddcn41.ticketing_system.domain.auth.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;

@Controller
public class PageController {

    /**
     * 관리자 로그인 페이지
     */
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
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> loginPage() throws IOException {
        Resource resource = new ClassPathResource("static/login.html");
        byte[] content = Files.readAllBytes(resource.getFile().toPath());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(content);
    }
}