package com.jparlant.model;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public record ChatRequest(
    String userId,
    String message,
    List<MultipartFile> files
) {

    public ChatRequest withMessage(String newMessage) {
        return new ChatRequest(this.userId, newMessage, this.files);
    }

    public boolean hasMedia() {
        return files != null && !files.isEmpty();
    }

}