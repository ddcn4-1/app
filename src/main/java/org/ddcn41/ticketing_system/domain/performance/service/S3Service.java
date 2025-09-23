package org.ddcn41.ticketing_system.domain.performance.service;

import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.dto.request.PresignedUrlRequest;
import org.ddcn41.ticketing_system.domain.performance.dto.response.PresignedUrlResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Operations s3Operations;
    private final S3Presigner s3Presigner;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    public PresignedUrlResponse getUploadImagePresignedURL(PresignedUrlRequest presignedUrlRequest) {
        String imageKey = generateFileName(getFileExtension(presignedUrlRequest.getImageName()), "performances/posters");

        return PresignedUrlResponse.builder()
                .presignedUrl(generateImageUploadPresignedUrl(imageKey, presignedUrlRequest.getImageType(), 5))
                .imageKey(imageKey)
                .build();
    }

    public String generateImageUploadPresignedUrl(String imageKey, String contentType, int expirationMinutes) {

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(imageKey)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest preSignedRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(expirationMinutes))
                .putObjectRequest(putObjectRequest)
                .build();

        return s3Presigner.presignPutObject(preSignedRequest).url().toString();
    }

    public String generateDownloadPresignedUrl(String imageKey, int expirationMinutes) {
        try {
            if (imageKey.isEmpty()) {
                return "";
            }

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(imageKey)
                    .build();

            GetObjectPresignRequest getPresignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expirationMinutes))
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(getPresignRequest).url().toString();

        } catch (Exception e) {
            throw new RuntimeException("이미지 URL 생성에 실패했습니다." + e.toString());
        }
    }

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
//            String key = extractKeyFromUrl(imageUrl);
            s3Operations.deleteObject(bucketName, imageUrl);
        } catch (Exception e) {
        }
    }

    /**
     * 고유한 파일명 생성
     */
    private String generateFileName(String type, String folder) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);

        return String.format("%s/%s/%s.%s", folder, timestamp, uuid, type);
    }

    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
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
