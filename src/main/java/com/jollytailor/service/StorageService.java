package com.jollytailor.service;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class StorageService {
    @Value("${app.storage.endpoint:}") private String endpoint;
    @Value("${app.storage.access-key-id:}") private String accessKey;
    @Value("${app.storage.secret-access-key:}") private String secretKey;
    @Value("${app.storage.project-url:}") private String projectUrl;
    @Value("${app.storage.bucket:tailor-catalog}") private String bucket;

    public String uploadPublicCatalogImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image first");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!type.equals("image/jpeg") && !type.equals("image/png") && !type.equals("image/webp") && !type.equals("image/gif"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only JPEG, PNG, WEBP or GIF images are allowed");
        if (file.getSize() > 8L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image limit is 8 MB");
        if (endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank() || projectUrl.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Supabase Storage is not configured yet");
        String ext = switch (type) { case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "gif"; };
        String key = "catalog/" + UUID.randomUUID() + "." + ext;
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).cacheControl("public, max-age=86400").build(), RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return key;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ResponseStatusException r) throw r;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image upload failed. Check Supabase S3 credentials and bucket permissions.");
        }
    }
    public String publicUrl(String key) {
        if (key == null || key.isBlank() || projectUrl.isBlank()) return null;
        return projectUrl.replaceAll("/$", "") + "/storage/v1/object/public/" + bucket + "/" + key;
    }
}
