package com.yuyuan.literature.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
public class ChatCompletionsRequest {
    private String model;
    private List<Message> messages;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @JsonProperty("response_format")
    private ResponseFormat responseFormat;
    private Double temperature;
    private Boolean stream;

    @Builder
    @Data
    public static class ResponseFormat {
        private String type;
    }

    @Data
    public static class Message {
        private String role;
        private String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
