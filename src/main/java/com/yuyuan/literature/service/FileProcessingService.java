package com.yuyuan.literature.service;

import com.yuyuan.literature.common.exception.BusinessException;
import com.yuyuan.literature.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件处理服务
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
public class FileProcessingService {

    @Value("${literature.file.upload-path:./uploads/documents}")
    private String uploadPath;

    @Value("${literature.file.allowed-extensions:pdf,doc,docx,md,markdown}")
    private String allowedExtensions;

    @Value("${literature.file.max-file-size:10MB}")
    private String maxFileSize;

    /**
     * 保存上传的文件并返回文件路径
     *
     * @param file 上传的文件
     * @return 保存后的文件路径
     */
    public String saveFile(MultipartFile file) {
        try {
            // 验证文件
            validateFile(file);

            // 确保上传目录存在
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = FilenameUtils.getExtension(originalFilename);
            String uniqueFilename = UUID.randomUUID().toString() + "." + extension;
            
            // 保存文件
            Path filePath = uploadDir.resolve(uniqueFilename);
            Files.copy(file.getInputStream(), filePath);
            
            log.info("文件保存成功: {}", filePath);
            return filePath.toString();
            
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR);
        }
    }

    /**
     * 解析文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    public String extractFileContent(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new BusinessException(ResultCode.FILE_NOT_EXIST);
            }

            String extension = FilenameUtils.getExtension(filePath).toLowerCase();
            
            return switch (extension) {
                case "pdf" -> extractPdfContent(file);
                case "doc" -> extractDocContent(file);
                case "docx" -> extractDocxContent(file);
                case "md", "markdown" -> extractMarkdownContent(file);
                default -> throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED);
            };
            
        } catch (Exception e) {
            log.error("文件内容解析失败: {}", filePath, e);
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException("文件内容解析失败: " + e.getMessage());
        }
    }

    /**
     * 验证上传的文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传的文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }

        // 验证文件扩展名
        String extension = FilenameUtils.getExtension(originalFilename).toLowerCase();
        List<String> allowedExtList = Arrays.asList(allowedExtensions.split(","));
        if (!allowedExtList.contains(extension)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED, 
                "只支持以下文件类型: " + allowedExtensions);
        }

        // 验证文件大小
        long maxSize = parseFileSize(maxFileSize);
        if (file.getSize() > maxSize) {
            throw new BusinessException(ResultCode.FILE_SIZE_EXCEEDED, 
                "文件大小超过限制: " + maxFileSize);
        }
    }

    /**
     * 解析 PDF 文件内容
     */
    private String extractPdfContent(File file) throws IOException {
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 解析 DOC 文件内容
     */
    private String extractDocContent(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 解析 DOCX 文件内容
     */
    private String extractDocxContent(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            StringBuilder content = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                content.append(paragraph.getText()).append("\n");
            }
            return content.toString();
        }
    }

    /**
     * 解析 Markdown 文件内容
     */
    private String extractMarkdownContent(File file) throws IOException {
        String markdownContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        
        // 使用 CommonMark 解析 Markdown 并转换为纯文本
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdownContent);
        TextContentRenderer renderer = TextContentRenderer.builder().build();
        
        return renderer.render(document);
    }

    /**
     * 解析文件大小字符串
     */
    private long parseFileSize(String sizeStr) {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            return 10 * 1024 * 1024; // 默认 10MB
        }
        
        sizeStr = sizeStr.trim().toLowerCase();
        long multiplier = 1;
        
        if (sizeStr.endsWith("kb")) {
            multiplier = 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("mb")) {
            multiplier = 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("gb")) {
            multiplier = 1024 * 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        }
        
        try {
            return Long.parseLong(sizeStr.trim()) * multiplier;
        } catch (NumberFormatException e) {
            return 10 * 1024 * 1024; // 默认 10MB
        }
    }

    /**
     * 删除文件
     */
    public void deleteFile(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
            log.info("文件删除成功: {}", filePath);
        } catch (IOException e) {
            log.warn("文件删除失败: {}", filePath, e);
        }
    }
}
