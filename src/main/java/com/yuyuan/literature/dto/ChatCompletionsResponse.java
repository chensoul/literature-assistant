package com.yuyuan.literature.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionsResponse {
    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    public String getFirstMessageContent() {
        if (choices == null || choices.isEmpty())
            return null;
        Message msg = choices.get(0).getMessage();
        return msg != null ? msg.getContent() : null;
    }

    public String getFirstDeltaContent() {
        if (choices == null || choices.isEmpty())
            return null;
        Delta d = choices.get(0).getDelta();
        return d != null ? d.getContent() : null;
    }

    public String getFirstFinishReason() {
        if (choices == null || choices.isEmpty())
            return null;
        Choice c = choices.get(0);
        return c.getFinishReason();
    }

    public boolean hasFinishReason() {
        return getFirstFinishReason() != null;
    }

    public boolean isFinishedByStop() {
        String r = getFirstFinishReason();
        return r != null && "stop".equalsIgnoreCase(r);
    }

    public boolean isFinishedByLength() {
        String r = getFirstFinishReason();
        return r != null && "length".equalsIgnoreCase(r);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Integer index;
        private Message message;
        private Delta delta;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {
        private String role;
        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
