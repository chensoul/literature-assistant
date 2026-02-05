package com.yuyuan.literature.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyuan.literature.common.exception.BusinessException;
import com.yuyuan.literature.common.result.ResultCode;
import com.yuyuan.literature.common.utils.JSONRepairUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 文献 AI 服务
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
public class LiteratureAiService {
    @Value("classpath:prompts/literature-guide-system-prompt.txt")
    private Resource systemPromptResource;
    @Value("classpath:prompts/literature-classification-system-prompt.txt")
    private Resource classificationPromptResource;

    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;

    public LiteratureAiService(ObjectMapper objectMapper, ChatClient.Builder builder) {
        this.objectMapper = objectMapper;
        this.chatClient = builder.build();
    }

    public String generateReadingGuide(String fileContent) {
        try {
            String content = chatClient.prompt()
                    .system(systemPromptResource.getContentAsString(UTF_8))
                    .user("请为以下文献生成阅读指南：\n\n" + fileContent)
                    .call()
                    .content();
            if (content == null || content.trim().isEmpty()) {
                throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "AI 返回内容为空");
            }
            log.info("阅读指南生成成功，内容长度: {}", content.length());
            return content;
        } catch (Exception e) {
            log.error("生成阅读指南失败", e);
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "生成阅读指南失败: " + e.getMessage());
        }
    }

    public Flux<String> generateReadingGuideFlux(String fileContent, Long literatureId,
                                                 LiteratureService literatureService) {
        String system = null;
        try {
            system = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String user = "请为以下文献生成阅读指南：\n\n" + fileContent;
        return chatClient.prompt()
                .system(system)
                .user(user)
                .stream()
                .content()
                .doOnNext(token -> {
                    try {
                        literatureService.updateReadingGuideAppend(literatureId, token);
                    } catch (Exception ex) {
                        log.warn("阅读指南追加写库失败，ID: {}", literatureId, ex);
                    }
                });
    }

    public void generateClassificationWithVirtualThread(String readingGuide, Long literatureId,
            LiteratureService literatureService) {
        // 使用虚拟线程
        Thread.startVirtualThread(() -> {
            try {
                String content = chatClient.prompt()
                        .system(classificationPromptResource.getContentAsString(StandardCharsets.UTF_8))
                        .user("请为以下文献阅读指南生成分类和描述：\n\n" + readingGuide)
                        .options(OpenAiChatOptions.builder().temperature(0.3).maxTokens(500).build())
                        .call()
                        .content();

                if (content == null || content.trim().isEmpty()) {
                    log.error("AI 返回的分类内容为空");
                    literatureService.updateStatus(literatureId,
                            com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                    return;
                }

                try {
                    String repaired = JSONRepairUtil.repair(content);
                    com.yuyuan.literature.dto.ClassificationResponse classification = objectMapper
                            .readValue(repaired, com.yuyuan.literature.dto.ClassificationResponse.class);
                    literatureService.updateClassification(literatureId, classification.getTags(),
                            classification.getDesc());
                    log.info("文献分类生成并保存成功，ID: {}, 标签数量: {}", literatureId,
                            classification.getTags() != null ? classification.getTags().size() : 0);
                } catch (Exception e) {
                    log.error("解析分类结果失败: {}", content, e);
                    literatureService.updateStatus(literatureId,
                            com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                }

            } catch (Exception e) {
                log.error("生成文献分类失败", e);
                literatureService.updateStatus(literatureId,
                        com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
            }
        });
    }

    public Mono<String> generateClassificationMono(String readingGuide, Long literatureId,
                                                   LiteratureService literatureService) {
        return Mono.fromCallable(() -> {
            String content = chatClient.prompt()
                    .system(classificationPromptResource.getContentAsString(StandardCharsets.UTF_8))
                    .user("请为以下文献阅读指南生成分类和描述：\n\n" + readingGuide)
                    .options(OpenAiChatOptions.builder().temperature(0.3).maxTokens(500).build())
                    .call()
                    .content();

            if (content == null || content.trim().isEmpty()) {
                literatureService.updateStatus(literatureId,
                        com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                return "分类生成失败：AI 返回内容为空";
            }

            try {
                String repaired = JSONRepairUtil.repair(content);
                com.yuyuan.literature.dto.ClassificationResponse classification = objectMapper
                        .readValue(repaired, com.yuyuan.literature.dto.ClassificationResponse.class);
                literatureService.updateClassification(literatureId, classification.getTags(),
                        classification.getDesc());
                return "分类生成完成";
            } catch (Exception e) {
                literatureService.updateStatus(literatureId,
                        com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                return "分类解析失败：" + e.getMessage();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

}
