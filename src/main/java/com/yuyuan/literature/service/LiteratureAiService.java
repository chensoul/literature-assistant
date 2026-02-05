package com.yuyuan.literature.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyuan.literature.common.exception.BusinessException;
import com.yuyuan.literature.common.result.ResultCode;
import com.yuyuan.literature.dto.ChatCompletionsRequest;
import com.yuyuan.literature.dto.ChatCompletionsResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 文献 AI 服务
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
public class LiteratureAiService {

    @Value("${literature.ai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${literature.ai.model:qwen-plus}")
    private String model;

    @Value("${literature.ai.max-tokens:4000}")
    private Integer maxTokens;

    @Value("${literature.ai.temperature:0.7}")
    private Double temperature;

    @Value("${literature.ai.timeout:60000}")
    private Integer timeout;

    @Value("${literature.ai.system-prompt-file}")
    private String systemPromptFile;

    @Value("${literature.ai.classification-prompt-file}")
    private String classificationPromptFile;

    private final OkHttpClient httpClient;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    // 缓存系统提示词内容
    private String cachedSystemPrompt;
    private String cachedClassificationPrompt;

    public LiteratureAiService(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                // 确保使用 UTF-8 编码
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder requestBuilder = original.newBuilder()
                            .header("Accept-Charset", "UTF-8");
                    return chain.proceed(requestBuilder.build());
                })
                .build();
    }

    /**
     * 获取系统提示词（带缓存）
     */
    private String getSystemPrompt() {
        if (cachedSystemPrompt == null) {
            cachedSystemPrompt = loadPromptFromFile(systemPromptFile);
        }
        return cachedSystemPrompt;
    }

    /**
     * 获取分类提示词（带缓存）
     * todo 这个提示词可以优化，目前的分类太分散，导致不好通过标签分类管理
     */
    private String getClassificationPrompt() {
        if (cachedClassificationPrompt == null) {
            cachedClassificationPrompt = loadPromptFromFile(classificationPromptFile);
        }
        return cachedClassificationPrompt;
    }

    /**
     * 从资源文件加载提示词内容
     */
    private String loadPromptFromFile(String filePath) {
        String resourcePath = filePath.replace("classpath:", "");

        // 使用 try-with-resources 确保流被正确关闭
        try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {

            if (inputStream == null) {
                log.error("无法在 classpath 中找到资源文件: {}", resourcePath);
                throw new BusinessException(ResultCode.ERROR, "系统提示词文件不存在: " + filePath);
            }

            // 使用 BufferedReader 逐行读取，效率更高
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                    BufferedReader bufferedReader = new BufferedReader(reader)) {

                StringBuilder contentBuilder = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    contentBuilder.append(line).append(System.lineSeparator());
                }

                String content = contentBuilder.toString();
                if (content == null || content.trim().isEmpty()) {
                    throw new BusinessException(ResultCode.ERROR, "系统提示词文件内容为空: " + filePath);
                }

                log.info("成功加载系统提示词文件: {}, 内容长度: {}", filePath, content.length());
                return content;
            }

        } catch (IOException e) {
            log.error("读取系统提示词文件失败: {}", filePath, e);
            throw new BusinessException(ResultCode.ERROR, "读取系统提示词文件失败: " + e.getMessage());
        }
    }

    /**
     * 生成文献阅读指南（非流式，用于批量导入）
     *
     * @param apiKey      API Key
     * @param fileContent 文献内容
     * @return 生成的阅读指南内容
     */
    public String generateReadingGuide(String apiKey, String fileContent) {
        try {
            // 构建请求
            ChatCompletionsRequest chatRequest = buildChatRequest(fileContent);
            chatRequest.setStream(false);
            String requestBody = objectMapper.writeValueAsString(chatRequest);

            log.info("发送 AI 非流式请求，模型: {}, 内容长度: {}", model, fileContent.length());

            Request request = createRequest(apiKey, requestBody, false);

            // 同步调用获取完整响应
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = "AI 请求失败: " + response.code() + " - " + response.message();
                    if (response.body() != null) {
                        errorMsg += " - " + response.body().string();
                    }
                    throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, errorMsg);
                }

                if (response.body() == null) {
                    throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "AI 响应体为空");
                }

                String responseBody = response.body().string();
                log.debug("AI 响应: {}", responseBody);
                ChatCompletionsResponse resp = objectMapper.readValue(responseBody, ChatCompletionsResponse.class);
                String content = resp.getFirstMessageContent();
                if (content == null)

                    if (content == null || content.trim().isEmpty()) {
                        throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "AI 返回内容为空");
                    }

                log.info("阅读指南生成成功，内容长度: {}", content.length());
                return content;

            } catch (IOException e) {
                log.error("AI 请求异常", e);
                throw new BusinessException(ResultCode.NETWORK_ERROR, "AI 服务请求异常: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("生成阅读指南失败", e);
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "生成阅读指南失败: " + e.getMessage());
        }
    }

    /**
     * 生成文献阅读指南（SSE 流式响应）
     *
     * @param apiKey            API Key
     * @param fileContent       文献内容
     * @param sseEmitter        SSE 发射器
     * @param literatureId      文献ID
     * @param literatureService 文献服务
     */
    public void generateReadingGuideStream(String apiKey, String fileContent, SseEmitter sseEmitter,
            Long literatureId, LiteratureService literatureService) {
        CompletableFuture.runAsync(() -> {
            try {
                // 构建请求
                ChatCompletionsRequest chatRequest = buildChatRequest(fileContent);
                String requestBody = objectMapper.writeValueAsString(chatRequest);

                log.info("发送 AI 请求，模型: {}, 内容长度: {}", model, fileContent.length());

                Request request = createRequest(apiKey, requestBody, true);

                // 创建 EventSource 监听器
                EventSourceListener listener = new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        log.info("AI SSE 连接已建立");
                    }

                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        try {
                            // 检查是否为结束标记
                            if ("[DONE]".equals(data)) {
                                sseEmitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data("生成完成"));
                                sseEmitter.complete();
                                return;
                            }

                            // 跳过空数据
                            if (data == null || data.trim().isEmpty()) {
                                return;
                            }

                            ChatCompletionsResponse chunk = objectMapper.readValue(data, ChatCompletionsResponse.class);
                            String content = chunk.getFirstDeltaContent();
                            String finishReason = chunk.getFirstFinishReason();

                            // 注意：不要使用 StrUtil.isNotBlank() 来检查内容
                            // 因为它会忽略只包含空格的字符串，这正是我们要保留的
                            if (content != null) {
                                sseEmitter.send(SseEmitter.event().name("content").data(content));
                                try {
                                    literatureService.updateReadingGuideAppend(literatureId, content);
                                } catch (Exception ex) {
                                    log.warn("阅读指南追加写库失败，ID: {}", literatureId, ex);
                                }
                                if (log.isDebugEnabled()) {
                                    String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
                                    log.debug("发送内容片段 [长度: {}]: '{}'", content.length(), preview);
                                }
                            }

                            if (finishReason != null) {
                                sseEmitter.send(SseEmitter.event().name("finish").data(finishReason));
                            }

                        } catch (Exception e) {
                            log.warn("处理 SSE 事件失败 - Data: {}", data, e);
                        }
                    }

                    @Override
                    public void onClosed(EventSource eventSource) {
                        log.info("AI SSE 连接已关闭");
                    }

                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        log.error("AI SSE 连接失败", t);
                        try {
                            String errorMsg = "AI 服务连接失败";
                            if (response != null) {
                                errorMsg += ": " + response.code() + " - " + response.message();
                                if (response.body() != null) {
                                    errorMsg += " - " + response.body().string();
                                }
                            }

                            sseEmitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(errorMsg));
                            sseEmitter.completeWithError(new BusinessException(errorMsg));
                        } catch (IOException e) {
                            log.error("发送错误消息失败", e);
                            sseEmitter.completeWithError(t);
                        }
                    }
                };

                // 创建 EventSource 并开始监听
                EventSource eventSource = EventSources.createFactory(httpClient)
                        .newEventSource(request, listener);

                // 设置 SSE 发射器的回调
                sseEmitter.onCompletion(() -> {
                    log.info("SSE 连接完成，关闭 EventSource");
                    eventSource.cancel();
                });

                sseEmitter.onError((throwable) -> {
                    log.error("SSE 连接错误，关闭 EventSource", throwable);
                    eventSource.cancel();
                });

                sseEmitter.onTimeout(() -> {
                    log.warn("SSE 连接超时，关闭 EventSource");
                    eventSource.cancel();
                });

            } catch (Exception e) {
                log.error("生成文献阅读指南失败", e);
                try {
                    sseEmitter.send(SseEmitter.event()
                            .name("error")
                            .data("生成文献阅读指南失败: " + e.getMessage()));
                    sseEmitter.completeWithError(e);
                } catch (IOException ioException) {
                    log.error("发送错误消息失败", ioException);
                    sseEmitter.completeWithError(ioException);
                }
            }
        });
    }

    /**
     * 构建聊天请求
     */
    private ChatCompletionsRequest buildChatRequest(String fileContent) {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setModel(model);
        request.setMaxTokens(maxTokens);
        request.setTemperature(temperature);
        request.setStream(true);

        // 构建消息，使用从文件加载的系统提示词
        request.setMessages(Arrays.asList(
                new ChatCompletionsRequest.Message("system", getSystemPrompt()),
                new ChatCompletionsRequest.Message("user", "请为以下文献生成阅读指南：\n\n" + fileContent)));

        return request;
    }

    /**
     * 生成文献分类和描述（使用虚拟线程）
     *
     * @param apiKey            API Key
     * @param readingGuide      阅读指南内容
     * @param literatureId      文献ID
     * @param literatureService 文献服务
     */
    public void generateClassificationWithVirtualThread(String apiKey, String readingGuide, Long literatureId,
            LiteratureService literatureService) {
        // 使用虚拟线程
        Thread.startVirtualThread(() -> {
            try {
                // 构建分类请求
                ChatCompletionsRequest chatRequest = buildClassificationRequest(readingGuide);
                String requestBody = objectMapper.writeValueAsString(chatRequest);

                log.info("发送文献分类请求，模型: {}, 内容长度: {}", model, readingGuide.length());

                Request request = createRequest(apiKey, requestBody, false);

                // 同步调用获取完整响应
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String errorMsg = "AI 分类请求失败: " + response.code() + " - " + response.message();
                        if (response.body() != null) {
                            errorMsg += " - " + response.body().string();
                        }
                        log.error(errorMsg);
                        literatureService.updateStatus(literatureId,
                                com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                        return;
                    }

                    if (response.body() == null) {
                        log.error("AI 响应内容为空");
                        literatureService.updateStatus(literatureId,
                                com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                        return;
                    }

                    String responseBody = response.body().string();
                    log.debug("AI 分类响应: {}", responseBody);
                    ChatCompletionsResponse resp = objectMapper.readValue(responseBody, ChatCompletionsResponse.class);
                    String content = resp.getFirstMessageContent();
                    if (content == null)
                        content = "";

                    if (content == null || content.trim().isEmpty()) {
                        log.error("AI 返回的分类内容为空");
                        literatureService.updateStatus(literatureId,
                                com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                        return;
                    }

                    // 解析分类结果并直接保存到数据库
                    try {
                        com.yuyuan.literature.dto.ClassificationResponse classification = objectMapper
                                .readValue(content.trim(), com.yuyuan.literature.dto.ClassificationResponse.class);

                        // 更新数据库
                        literatureService.updateClassification(literatureId, classification.getTags(),
                                classification.getDesc());
                        log.info("文献分类生成并保存成功，ID: {}, 标签数量: {}", literatureId, classification.getTags().size());

                    } catch (Exception e) {
                        log.error("解析分类结果失败: {}", content, e);
                        // 即使分类失败，也标记为完成状态
                        literatureService.updateStatus(literatureId,
                                com.yuyuan.literature.entity.Literature.Status.COMPLETED.getCode());
                    }

                } catch (IOException e) {
                    log.error("调用 AI 分类服务失败", e);
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

    /**
     * 构建分类请求
     */
    private ChatCompletionsRequest buildClassificationRequest(String readingGuide) {
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setModel(model);
        request.setMaxTokens(500); // 分类响应不需要太多 token
        request.setResponseFormat(
                ChatCompletionsRequest.ResponseFormat.builder()
                        .type("json_object")
                        .build());
        request.setTemperature(0.3); // 降低温度，提高准确性
        request.setStream(false); // 非流式响应

        // 构建消息，使用从文件加载的分类提示词
        request.setMessages(Arrays.asList(
                new ChatCompletionsRequest.Message("system", getClassificationPrompt()),
                new ChatCompletionsRequest.Message("user", "请为以下文献阅读指南生成分类和描述：\n\n" + readingGuide)));

        return request;
    }

    /**
     * 验证 API Key
     */
    public void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "API Key 不能为空");
        }

        if (apiKey.length() < 10) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "API Key 格式错误");
        }
    }

    private Request createRequest(String apiKey, String requestBody, boolean sse) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")));
        if (sse) {
            builder.header("Accept", "text/event-stream").header("Cache-Control", "no-cache");
        }
        return builder.build();
    }
}
