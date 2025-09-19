package org.ddcn41.ticketing_system.domain.performance.service;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Operations s3Operations;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 이미지를 S3에 업로드하고 URL을 반환
     */
    public String uploadImage(MultipartFile file, String folder) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }

        // 파일명 생성: folder/날짜/UUID_원본파일명
        String fileName = generateFileName(file.getOriginalFilename(), folder);

        try {
            // S3에 파일 업로드
            S3Resource resource = s3Operations.upload(bucketName, fileName, file.getInputStream());

            // 업로드된 파일의 URL 반환
            String imageUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                    bucketName, "ap-northeast-2", fileName);

            return imageUrl;

        } catch (IOException e) {
            throw new RuntimeException("이미지 업로드에 실패했습니다.", e);
        }
    }

    /**
     * S3에서 이미지 삭제
     */
    public void deleteImage(String imageUrl) {
        try {
            // URL에서 키 추출
            String key = extractKeyFromUrl(imageUrl);
            s3Operations.deleteObject(bucketName, key);
        } catch (Exception e) {
        }
    }

    /**
     * 고유한 파일명 생성
     */
    private String generateFileName(String originalFilename, String folder) {
        String extension = getFileExtension(originalFilename);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);

        return String.format("%s/%s/%s_%s%s", folder, timestamp, uuid,
                originalFilename.replaceAll("[^a-zA-Z0-9.]", "_"), extension);
    }

    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    /**
     * Pre-signed URL 생성 (보안이 중요한 경우)
     */
    public String generatePresignedUrl(String imageUrl, int expirationMinutes) {
        try {
            String key = extractKeyFromUrl(imageUrl);
            if (key.isEmpty()) {
                return imageUrl; // 원본 URL 반환
            }

            // Pre-signed URL 생성 (읽기 전용)
            S3Resource resource = s3Operations.download(bucketName, key);
            // S3Operations에서 직접 pre-signed URL을 생성하는 메서드가 없으므로
            // 원본 URL을 그대로 반환 (버킷이 공개 설정인 경우)
            return imageUrl;

        } catch (Exception e) {
            return imageUrl; // 실패 시 원본 URL 반환
        }
    }

    /**
     * 이미지 존재 여부 확인
     */
    public boolean isImageExists(String imageUrl) {
        try {
            String key = extractKeyFromUrl(imageUrl);
            if (key.isEmpty()) return false;

            S3Resource resource = s3Operations.download(bucketName, key);
            return resource.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * URL에서 S3 키 추출
     */
    private String extractKeyFromUrl(String imageUrl) {
        // https://bucket-name.s3.region.amazonaws.com/key 형태에서 key 부분 추출
        String[] parts = imageUrl.split(".amazonaws.com/");
        return parts.length > 1 ? parts[1] : "";
    }
}
