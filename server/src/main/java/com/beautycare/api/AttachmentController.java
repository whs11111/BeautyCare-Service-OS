package com.beautycare.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final Path directory = Path.of(System.getProperty("user.home"), ".beautycare-service-os", "attachments");

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件为空");
        String id = UUID.randomUUID().toString();
        Files.createDirectories(directory);
        Files.copy(file.getInputStream(), directory.resolve(id));
        String name = file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename().replaceAll(".*[\\\\/]", "");
        Files.writeString(directory.resolve(id + ".name"), name);
        String type = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        Files.writeString(directory.resolve(id + ".type"), type);
        return Map.of("name", name, "size", file.getSize(), "type", type, "url", "/api/attachments/" + id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean open,
            @RequestParam(defaultValue = "false") boolean download) throws IOException {
        try { UUID.fromString(id); } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path path = directory.resolve(id);
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        String name = Files.readString(directory.resolve(id + ".name"));
        String type = Files.readString(directory.resolve(id + ".type"));
        // Only raster images may render inline; other uploads must download.
        boolean image = type.matches("image/(png|jpeg|gif|webp|bmp)");
        boolean safePreview = type.equals("application/pdf") || type.equals("text/plain")
                || type.equals("text/markdown") || type.equals("text/csv")
                || type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || type.equals("application/vnd.ms-excel") || type.equals("application/msword");
        boolean inline = !download && (image || (open && safePreview));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(inline ? (type.startsWith("text/") ? "text/plain;charset=UTF-8" : type) : "application/octet-stream"))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }
}
