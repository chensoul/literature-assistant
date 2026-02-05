package com.yuyuan.literature.controller;

import com.yuyuan.literature.common.request.PageResult;
import com.yuyuan.literature.common.result.Result;
import com.yuyuan.literature.dto.BatchLiteratureImportRequest;
import com.yuyuan.literature.dto.LiteratureQueryRequest;
import com.yuyuan.literature.dto.LiteratureVO;
import com.yuyuan.literature.entity.Literature;
import com.yuyuan.literature.service.FileProcessingService;
import com.yuyuan.literature.service.LiteratureAiService;
import com.yuyuan.literature.service.LiteratureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 文献助手控制器
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/literature")
@Tag(name = "文献助手", description = "文献分析和阅读指南生成")
@RequiredArgsConstructor
@Validated
public class LiteratureController {

        private final FileProcessingService fileProcessingService;
        private final LiteratureAiService literatureAiService;
        private final LiteratureService literatureService;

        /**
         * 生成文献阅读指南
         *
         * @param file 上传的文献文件
         * @return SSE 流式响应
         */
        @PostMapping(value = "/generate-guide", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @Operation(summary = "生成文献阅读指南", description = "上传文献文件，AI 生成阅读指南（SSE 流式响应）")
        public Flux<ServerSentEvent<String>> generateReadingGuide(
                        @Parameter(description = "文献文件（支持 PDF、Word、Markdown）", required = true) @RequestParam("file") MultipartFile file) {

                return Flux.defer(() -> {
                        Flux<ServerSentEvent<String>> start = Flux
                                        .just(ServerSentEvent.<String>builder().event("start").data("开始处理文献文件...")
                                                        .build());

                        Flux<ServerSentEvent<String>> pipeline = Mono
                                        .fromCallable(() -> fileProcessingService.saveFile(file))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMapMany(filePath -> {
                                                Flux<ServerSentEvent<String>> progress1 = Flux
                                                                .just(ServerSentEvent.<String>builder()
                                                                                .event("progress")
                                                                                .data("文件保存成功，开始解析内容...").build());
                                                Flux<ServerSentEvent<String>> sequence = Mono
                                                                .fromCallable(() -> fileProcessingService
                                                                                .extractFileContent(filePath))
                                                                .subscribeOn(Schedulers.boundedElastic())
                                                                .flatMapMany(fileContent -> Mono
                                                                                .fromCallable(() -> literatureService
                                                                                                .createLiterature(file,
                                                                                                                filePath,
                                                                                                                fileContent.length()))
                                                                                .subscribeOn(Schedulers
                                                                                                .boundedElastic())
                                                                                .flatMapMany(literatureId -> {
                                                                                        Flux<ServerSentEvent<String>> progress2 = Flux
                                                                                                        .just(ServerSentEvent
                                                                                                                        .<String>builder()
                                                                                                                        .event("progress")
                                                                                                                        .data("内容解析成功，开始生成阅读指南...")
                                                                                                                        .build());
                                                                                        Flux<ServerSentEvent<String>> contentEvents = literatureAiService
                                                                                                        .generateReadingGuideFlux(
                                                                                                                        fileContent,
                                                                                                                        literatureId,
                                                                                                                        literatureService)
                                                                                                        .map(token -> ServerSentEvent
                                                                                                                        .<String>builder()
                                                                                                                        .event("content")
                                                                                                                        .data(token)
                                                                                                                        .build());
                                                                                        contentEvents = contentEvents.doOnComplete(() -> {
                                                                                            String readingGuide = literatureService.getById(literatureId)
                                                                                                    .getReadingGuide();
                                                                                            if (readingGuide != null && !readingGuide.trim().isEmpty()) {
                                                                                                literatureService.updateReadingGuide(literatureId, readingGuide);
                                                                                                literatureAiService.generateClassificationWithVirtualThread(
                                                                                                        readingGuide, literatureId, literatureService);
                                                                                            } else {
                                                                                                literatureService.updateStatus(literatureId,
                                                                                                        Literature.Status.COMPLETED.getCode());
                                                                                            }
                                                                                        });
                                                                                        Flux<ServerSentEvent<String>> complete = Flux
                                                                                                        .just(ServerSentEvent
                                                                                                                        .<String>builder()
                                                                                                                        .event("complete")
                                                                                                                        .data("生成完成")
                                                                                                                        .build());
                                                                                        return Flux.concat(progress2,
                                                                                                        contentEvents,
                                                                                                        complete);
                                                                                }));
                                                return Flux.concat(progress1, sequence);
                                        })
                                        .onErrorResume(error -> Flux
                                                        .just(ServerSentEvent.<String>builder().event("error")
                                                                        .data("处理失败: " + error.getMessage()).build()));

                        return Flux.concat(start, pipeline);
                });
        }

        /**
         * 分页查询文献
         */
        @PostMapping("/page")
        @Operation(summary = "分页查询文献", description = "根据条件分页查询文献列表")
        public Result<PageResult<LiteratureVO>> pageLiteratures(
                        @Valid @RequestBody LiteratureQueryRequest request) {

                log.info("分页查询文献，条件: {}", request);

                PageResult<LiteratureVO> result = literatureService.pageLiteratures(request);
                return Result.success(result);
        }

        /**
         * 获取文献详情
         */
        @GetMapping("/{id}")
        @Operation(summary = "获取文献详情", description = "根据ID获取文献完整信息")
        public Result<LiteratureVO> getLiteratureDetail(
                        @Parameter(description = "文献ID", required = true) @PathVariable @NotNull(message = "文献ID不能为空") Long id) {

                log.info("获取文献详情，ID: {}", id);

                LiteratureVO result = literatureService.getLiteratureDetail(id);
                return Result.success(result);
        }

        /**
         * 下载文献文件
         */
        @GetMapping("/{id}/download")
        @Operation(summary = "下载文献文件", description = "根据文献ID下载对应的原始文件")
        public void downloadLiteratureFile(
                        @Parameter(description = "文献ID", required = true) @PathVariable @NotNull(message = "文献ID不能为空") Long id,
                        jakarta.servlet.http.HttpServletResponse response) {

                log.info("下载文献文件，ID: {}", id);
                literatureService.downloadLiteratureFile(id, response);
        }

        /**
         * 批量导入文献
         */
        @PostMapping(value = "/batch-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @Operation(summary = "批量导入文献", description = "批量上传文献文件，AI生成阅读指南并实时返回处理状态")
        public Flux<ServerSentEvent<String>> batchImportLiterature(
                        @Parameter(description = "文献文件列表", required = true) @RequestPart("files") List<MultipartFile> files) {

                BatchLiteratureImportRequest request = new BatchLiteratureImportRequest();
                request.setFiles(files);

                return literatureService.batchImportLiterature(request);
        }

        /**
         * 健康检查接口
         */
        @GetMapping("/health")
        @Operation(summary = "健康检查", description = "检查文献助手服务状态")
        public String health() {
                return "Literature Assistant is running!";
        }
}
