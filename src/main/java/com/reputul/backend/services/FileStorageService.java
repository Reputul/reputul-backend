package com.reputul.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${file.upload.dir:./uploads/logos}")
    private String uploadDir;

    /**
     * Store a file and return the filename
     */
    public String storeFile(MultipartFile file, String prefix) throws IOException {
        // Create upload directory if it doesn't exist
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // Generate unique filename
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String filename = prefix + "_" + UUID.randomUUID().toString() + extension;

        // Store file
        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        return filename;
    }

    /**
     * Delete a file
     */
    public void deleteFile(String filename) throws IOException {
        if (filename == null) return;

        Path filePath = Paths.get(uploadDir).resolve(filename);
        Files.deleteIfExists(filePath);
    }

    /**
     * Get file path
     */
    public Path getFilePath(String filename) {
        return Paths.get(uploadDir).resolve(filename).normalize();
    }
}