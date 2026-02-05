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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
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
    public Flux<ServerSentEvent<String>> batchImportLiterature(BatchLiteratureImportRequest request) {
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        int total = request.getFiles().size();

        Flux<ServerSentEvent<String>> start = Flux.just(ServerSentEvent.<String>builder()
                .event("batch_start")
                .data("{\"total\": " + total + ", \"message\": \"开始批量处理\"}")
                .build());

        Flux<ServerSentEvent<String>> processing = Flux.range(0, total)
                .concatMap(index -> {
                    MultipartFile file = request.getFiles().get(index);
                    Flux<ServerSentEvent<String>> fileStart = Flux.just(ServerSentEvent.<String>builder()
                            .event("file_start")
                            .data("{\"index\": " + index + ", \"filename\": \"" + file.getOriginalFilename()
                                    + "\", \"message\": \"开始处理文件\"}")
                            .build());
                    Mono<String> filePathMono = Mono.fromCallable(() -> fileProcessingService.saveFile(file))
                            .subscribeOn(Schedulers.boundedElastic());
                    Mono<String> fileContentMono = filePathMono
                            .flatMap(fp -> Mono.fromCallable(() -> fileProcessingService.extractFileContent(fp))
                                    .subscribeOn(Schedulers.boundedElastic()));
                    Mono<Long> literatureIdMono = filePathMono.zipWith(fileContentMono)
                            .flatMap(tuple -> Mono.fromCallable(
                                            () -> this.createLiterature(file, tuple.getT1(), tuple.getT2().length()))
                                    .subscribeOn(Schedulers.boundedElastic()));
                    Mono<ServerSentEvent<String>> savedEvent = literatureIdMono.map(literatureId -> ServerSentEvent
                            .<String>builder()
                            .event("file_saved")
                            .data("{\"index\": " + index + ", \"literatureId\": " + literatureId
                                    + ", \"message\": \"文件保存成功，开始生成阅读指南\"}")
                            .build());
                    Mono<ServerSentEvent<String>> result = literatureIdMono
                            .zipWith(fileContentMono)
                            .flatMap(tuple -> Mono.fromCallable(() -> {
                                Long literatureId = tuple.getT1();
                                String fileContent = tuple.getT2();
                                String readingGuide = literatureAiService.generateReadingGuide(fileContent);
                                if (readingGuide != null && !readingGuide.trim().isEmpty()) {
                                    this.updateReadingGuide(literatureId, readingGuide);
                                    literatureAiService.generateClassificationWithVirtualThread(readingGuide,
                                            literatureId, this);
                                } else {
                                    this.updateStatus(literatureId, Literature.Status.COMPLETED.getCode());
                                }
                                int completed = completedCount.incrementAndGet();
                                return ServerSentEvent.<String>builder()
                                        .event("file_complete")
                                        .data("{\"index\": " + index + ", \"literatureId\": " + literatureId
                                                + ", \"completed\": " + completed + ", \"total\": " + total
                                                + ", \"message\": \"文件处理完成\"}")
                                        .build();
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .onErrorResume(e -> {
                                int completed = completedCount.incrementAndGet();
                                int errors = errorCount.incrementAndGet();
                                return Mono.just(ServerSentEvent.<String>builder()
                                        .event("file_error")
                                        .data("{\"index\": " + index + ", \"filename\": \""
                                                + file.getOriginalFilename() + "\", \"error\": \"" + e.getMessage()
                                                + "\", \"completed\": " + completed + ", \"total\": " + total + "}")
                                        .build());
                            });
                    return Flux.concat(fileStart, savedEvent, result);
                });

        Flux<ServerSentEvent<String>> completion = Flux.just(ServerSentEvent.<String>builder()
                .event("batch_complete")
                .data("{\"message\": \"批量处理完成\", \"total\": " + total + ", \"errors\": " + errorCount.get() + "}")
                .build());

        return Flux.concat(start, processing, completion);
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
