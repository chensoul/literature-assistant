package com.yuyuan.literature.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuyuan.literature.common.exception.BusinessException;
import com.yuyuan.literature.common.request.PageResult;
import com.yuyuan.literature.common.result.ResultCode;
import com.yuyuan.literature.dto.BatchLiteratureImportRequest;
import com.yuyuan.literature.dto.LiteratureQueryRequest;
import com.yuyuan.literature.dto.LiteratureVO;
import com.yuyuan.literature.entity.Literature;
import com.yuyuan.literature.mapper.LiteratureMapper;
import com.yuyuan.literature.service.FileProcessingService;
import com.yuyuan.literature.service.LiteratureAiService;
import com.yuyuan.literature.service.LiteratureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 文献服务实现类
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiteratureServiceImpl extends ServiceImpl<LiteratureMapper, Literature> implements LiteratureService {

    private final FileProcessingService fileProcessingService;
    private final LiteratureAiService literatureAiService;

    // 虚拟线程池，用于并发处理文件
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Long createLiterature(MultipartFile file, String filePath, Integer contentLength) {
        Literature literature = new Literature();
        literature.setOriginalName(file.getOriginalFilename());
        literature.setFilePath(filePath);
        literature.setFileSize(file.getSize());
        String ext = org.apache.commons.io.FilenameUtils.getExtension(file.getOriginalFilename());
        literature.setFileType(ext != null ? ext.toLowerCase() : "");
        literature.setContentLength(contentLength);
        literature.setStatus(Literature.Status.PROCESSING.getCode());

        this.save(literature);
        log.info("创建文献记录成功，ID: {}, 文件名: {}", literature.getId(), literature.getOriginalName());

        return literature.getId();
    }

    @Override
    public void updateReadingGuide(Long id, String readingGuide) {
        Literature literature = new Literature();
        literature.setId(id);
        literature.setReadingGuide(readingGuide);

        this.updateById(literature);
        log.info("更新文献阅读指南成功，ID: {}", id);
    }

    @Override
    public void updateReadingGuideAppend(Long id, String chunk) {
        Literature current = this.getById(id);
        String original = current != null ? current.getReadingGuide() : null;
        String combined = (original == null ? "" : original) + (chunk == null ? "" : chunk);
        Literature update = new Literature();
        update.setId(id);
        update.setReadingGuide(combined);
        this.updateById(update);
    }

    @Override
    public void updateClassification(Long id, List<String> tags, String description) {
        Literature literature = new Literature();
        literature.setId(id);
        literature.setTags(tags);
        literature.setDescription(description);
        literature.setStatus(Literature.Status.COMPLETED.getCode());

        this.updateById(literature);
        log.info("更新文献分类成功，ID: {}, 标签数量: {}", id, tags.size());
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Literature literature = new Literature();
        literature.setId(id);
        literature.setStatus(status);

        this.updateById(literature);
        log.info("更新文献状态成功，ID: {}, 状态: {}", id, status);
    }

    @Override
    public PageResult<LiteratureVO> pageLiteratures(LiteratureQueryRequest request) {
        // 创建分页对象
        Page<Literature> page = new Page<>(request.getPageNum(), request.getPageSize());

        // 执行分页查询
        IPage<Literature> pageResult = baseMapper.selectLiteraturePage(page, request);

        // 转换为 VO 对象
        List<LiteratureVO> voList = pageResult.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, pageResult.getTotal(), request.getPageNum(), request.getPageSize());
    }

    @Override
    public LiteratureVO getLiteratureDetail(Long id) {
        Literature literature = this.getById(id);
        if (literature == null) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在");
        }

        return convertToDetailVO(literature);
    }

    /**
     * 转换为列表 VO（不包含完整阅读指南）
     */
    private LiteratureVO convertToVO(Literature literature) {
        LiteratureVO vo = new LiteratureVO();
        vo.setId(literature.getId());
        vo.setOriginalName(literature.getOriginalName());
        vo.setFileSize(literature.getFileSize());
        vo.setFileType(literature.getFileType());
        vo.setContentLength(literature.getContentLength());
        vo.setTags(literature.getTags());
        vo.setDescription(literature.getDescription());
        vo.setStatus(literature.getStatus());
        vo.setStatusDesc(getStatusDesc(literature.getStatus()));
        vo.setCreateTime(literature.getCreateTime());
        vo.setUpdateTime(literature.getUpdateTime());

        // 阅读指南摘要（截取前200个字符）
        if (literature.getReadingGuide() != null && !literature.getReadingGuide().trim().isEmpty()) {
            String summary = literature.getReadingGuide().length() > 200
                    ? literature.getReadingGuide().substring(0, 200) + "..."
                    : literature.getReadingGuide();
            vo.setReadingGuideSummary(summary);
        }

        return vo;
    }

    /**
     * 转换为详情 VO（包含完整信息）
     */
    private LiteratureVO convertToDetailVO(Literature literature) {
        LiteratureVO vo = convertToVO(literature);
        // 详情页面可以返回完整的阅读指南
        vo.setReadingGuideSummary(literature.getReadingGuide());
        return vo;
    }

    @Override
    public void downloadLiteratureFile(Long id, HttpServletResponse response) {
        log.info("开始下载文献文件，ID: {}", id);

        // 查询文献信息
        Literature literature = this.getById(id);
        if (literature == null) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在");
        }

        // 检查文件是否存在
        if (literature.getFilePath() == null || literature.getFilePath().trim().isEmpty()) {
            throw new BusinessException(ResultCode.FILE_NOT_EXIST, "文件路径为空");
        }

        // 处理文件路径，支持相对路径和绝对路径
        String filePath = literature.getFilePath();
        java.io.File file;

        // 如果是相对路径，则相对于项目根目录
        if (filePath.startsWith("./") || !filePath.startsWith("/")) {
            // 获取项目根目录
            String projectRoot = System.getProperty("user.dir");
            file = Paths.get(projectRoot, filePath).normalize().toFile();
        } else {
            // 绝对路径直接使用
            file = new File(filePath);
        }

        if (!file.exists()) {
            throw new BusinessException(ResultCode.FILE_NOT_EXIST, "文件不存在: " + file.getAbsolutePath());
        }

        try {
            String fileName = literature.getOriginalName();
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            // 设置响应头
            response.setContentType("application/octet-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName);
            response.setHeader("Content-Length", String.valueOf(file.length()));

            // 下载文件
            try (OutputStream os = response.getOutputStream()) {
                Files.copy(file.toPath(), os);
                os.flush();
            }

            log.info("文献文件下载成功，ID: {}, 文件名: {}, 文件大小: {} bytes",
                    id, fileName, file.length());
        } catch (Exception e) {
            log.error("下载文献文件失败，ID: {}", id, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public SseEmitter batchImportLiterature(BatchLiteratureImportRequest request) {
        log.info("开始批量导入文献，文件数量: {}", request.getFiles().size());

        // 30分钟超时
        SseEmitter sseEmitter = new SseEmitter(30 * 60 * 1000L);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger totalCount = new AtomicInteger(request.getFiles().size());
        AtomicInteger errorCount = new AtomicInteger(0);

        // 验证API Key
        literatureAiService.validateApiKey(request.getApiKey());

        // 发送批量开始事件
        sendSseEvent(sseEmitter, "batch_start", "{\"total\": " + totalCount.get() + ", \"message\": \"开始批量处理\"}");

        // 使用CompletableFuture + 虚拟线程池并发处理每个文件
        List<CompletableFuture<Void>> futures = request.getFiles().stream()
                .map(file -> CompletableFuture.runAsync(() -> {
                    try {
                        int fileIndex = request.getFiles().indexOf(file);

                        // 发送文件开始处理事件
                        sendSseEvent(sseEmitter, "file_start",
                                "{\"index\": " + fileIndex + ", \"filename\": \"" + file.getOriginalFilename()
                                        + "\", \"message\": \"开始处理文件\"}");

                        // 保存文件
                        String filePath = fileProcessingService.saveFile(file);

                        // 解析文件内容
                        String fileContent = fileProcessingService.extractFileContent(filePath);

                        // 创建文献记录
                        Long literatureId = this.createLiterature(file, filePath, fileContent.length());

                        // 发送文件保存成功事件
                        sendSseEvent(sseEmitter, "file_saved",
                                "{\"index\": " + fileIndex + ", \"literatureId\": " + literatureId
                                        + ", \"message\": \"文件保存成功，开始生成阅读指南\"}");

                        // 生成阅读指南（非流式）
                        String readingGuide = literatureAiService.generateReadingGuide(request.getApiKey(),
                                fileContent);

                        // 更新阅读指南
                        if (readingGuide != null && !readingGuide.trim().isEmpty()) {
                            this.updateReadingGuide(literatureId, readingGuide);

                            // 异步生成分类
                            literatureAiService.generateClassificationWithVirtualThread(
                                    request.getApiKey(), readingGuide, literatureId, this);
                        } else {
                            this.updateStatus(literatureId, Literature.Status.COMPLETED.getCode());
                        }

                        // 发送文件完成事件
                        int completed = completedCount.incrementAndGet();
                        sendSseEvent(sseEmitter, "file_complete",
                                "{\"index\": " + fileIndex + ", \"literatureId\": " + literatureId + ", \"completed\": "
                                        + completed + ", \"total\": " + totalCount.get()
                                        + ", \"message\": \"文件处理完成\"}");

                        // 检查是否所有文件都处理完成
                        if (completed == totalCount.get()) {
                            sendSseEvent(sseEmitter, "batch_complete",
                                    "{\"message\": \"批量处理完成\", \"total\": " + totalCount.get() + ", \"errors\": "
                                            + errorCount.get() + "}");
                            sseEmitter.complete();
                        }

                    } catch (Exception e) {
                        log.error("处理文件失败，文件名: {}", file.getOriginalFilename(), e);

                        try {
                            // 发送文件失败事件
                            int fileIndex = request.getFiles().indexOf(file);
                            int completed = completedCount.incrementAndGet();
                            int errors = errorCount.incrementAndGet();

                            sendSseEvent(sseEmitter, "file_error",
                                    "{\"index\": " + fileIndex + ", \"filename\": \"" + file.getOriginalFilename()
                                            + "\", \"error\": \"" + e.getMessage() + "\", \"completed\": " + completed
                                            + ", \"total\": " + totalCount.get() + "}");

                            // 检查是否所有文件都处理完成（包括失败的）
                            if (completed == totalCount.get()) {
                                sendSseEvent(sseEmitter, "batch_complete",
                                        "{\"message\": \"批量处理完成（部分失败）\", \"total\": " + totalCount.get()
                                                + ", \"errors\": " + errors + "}");
                                sseEmitter.complete();
                            }
                        } catch (Exception sendException) {
                            log.error("发送错误事件失败", sendException);
                        }
                    }
                }, virtualThreadExecutor))
                .collect(Collectors.toList());

        // 设置SSE回调
        sseEmitter.onCompletion(() -> {
            log.info("批量导入SSE连接完成");
        });

        sseEmitter.onError((throwable) -> {
            log.error("批量导入SSE连接错误", throwable);
        });

        sseEmitter.onTimeout(() -> {
            log.warn("批量导入SSE连接超时");
        });

        return sseEmitter;
    }

    /**
     * 安全发送SSE事件
     */
    private void sendSseEvent(SseEmitter sseEmitter, String eventName, String data) {
        try {
            sseEmitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.warn("发送SSE事件失败: {} - {}", eventName, e.getMessage());
        }
    }

    /**
     * 获取状态描述
     */
    private String getStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }

        return switch (status) {
            case 0 -> "处理中";
            case 1 -> "已完成";
            case 2 -> "处理失败";
            default -> "未知";
        };
    }
}
