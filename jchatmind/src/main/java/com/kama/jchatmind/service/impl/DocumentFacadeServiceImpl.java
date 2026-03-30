package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.PdfParserService;
import com.kama.jchatmind.service.RagService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final PdfParserService pdfParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObservationRegistry observationRegistry;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    @Observed(name = "document.upload", contextualName = "upload-document")
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // 根据不同的文件类型进行处理并生成 chunks
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                processMarkdownDocument(kbId, documentId, filePath);
            } else if ("pdf".equalsIgnoreCase(filetype)) {
                processPdfDocument(kbId, documentId, filePath);
            } else if ("txt".equalsIgnoreCase(filetype)) {
                processTxtDocument(kbId, documentId, filePath);
            } else {
                // TODO: 未来可以增加其他文件类型的处理逻辑
                log.warn("待新增处理的文件类型: {}", filetype);
            }

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        Observation.createNotStarted("document.parse.markdown", observationRegistry)
                .lowCardinalityKeyValue("kb.id", kbId)
                .lowCardinalityKeyValue("document.id", documentId)
                .observe(() -> {
                    try {
                        log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

                        Path path = documentStorageService.getFilePath(filePath);
                        try (InputStream inputStream = Files.newInputStream(path)) {
                            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

                            if (sections.isEmpty()) {
                                log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                                return;
                            }

                            LocalDateTime now = LocalDateTime.now();
                            int chunkCount = 0;

                            for (MarkdownParserService.MarkdownSection section : sections) {
                                String title = section.getTitle();
                                String content = section.getContent();

                                if (title == null || title.trim().isEmpty()) {
                                    continue;
                                }

                                float[] embedding = ragService.embed(title);

                                ChunkBgeM3 chunk = ChunkBgeM3.builder()
                                        .kbId(kbId)
                                        .docId(documentId)
                                        .content(content != null ? content : "")
                                        .metadata(null)
                                        .embedding(embedding)
                                        .createdAt(now)
                                        .updatedAt(now)
                                        .build();

                                int result = chunkBgeM3Mapper.insert(chunk);

                                if (result > 0) {
                                    chunkCount++;
                                    log.debug("创建 chunk 成功: title={}, chunkId={}", title, chunk.getId());
                                } else {
                                    log.warn("创建 chunk 失败: title={}", title);
                                }
                            }
                            log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
                        }
                    } catch (Exception e) {
                        log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
                    }
                });
    }

    /**
     * 处理 PDF 文档，解析并生成 chunks
     */
    private void processPdfDocument(String kbId, String documentId, String filePath) {
        Observation.createNotStarted("document.parse.pdf", observationRegistry)
                .lowCardinalityKeyValue("kb.id", kbId)
                .lowCardinalityKeyValue("document.id", documentId)
                .observe(() -> {
                    try {
                        log.info("开始处理 PDF 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);
                        Path path = documentStorageService.getFilePath(filePath);
                        try (InputStream inputStream = Files.newInputStream(path)) {
                            List<PdfParserService.TextSection> sections = pdfParserService.parsePdf(inputStream);
                            saveChunks(kbId, documentId, sections, "PDF");
                        }
                    } catch (Exception e) {
                        log.error("处理 PDF 文档失败: documentId={}", documentId, e);
                    }
                });
    }

    /**
     * 处理 TXT 文档，解析并生成 chunks
     */
    private void processTxtDocument(String kbId, String documentId, String filePath) {
        Observation.createNotStarted("document.parse.txt", observationRegistry)
                .lowCardinalityKeyValue("kb.id", kbId)
                .lowCardinalityKeyValue("document.id", documentId)
                .observe(() -> {
                    try {
                        log.info("开始处理 TXT 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);
                        Path path = documentStorageService.getFilePath(filePath);
                        try (InputStream inputStream = Files.newInputStream(path)) {
                            List<PdfParserService.TextSection> sections = pdfParserService.parseTxt(inputStream);
                            saveChunks(kbId, documentId, sections, "TXT");
                        }
                    } catch (Exception e) {
                        log.error("处理 TXT 文档失败: documentId={}", documentId, e);
                    }
                });
    }

    /**
     * 公共方法：保存通用的 TextSection 到数据库
     */
    private void saveChunks(String kbId, String documentId, List<PdfParserService.TextSection> sections, String format) {
        Observation.createNotStarted("document.chunk.persist", observationRegistry)
                .lowCardinalityKeyValue("kb.id", kbId)
                .lowCardinalityKeyValue("document.id", documentId)
                .lowCardinalityKeyValue("document.type", format)
                .observe(() -> {
                    if (sections == null || sections.isEmpty()) {
                        log.warn("{} 文档解析后没有找到任何章节: documentId={}", format, documentId);
                        return;
                    }

                    LocalDateTime now = LocalDateTime.now();
                    int chunkCount = 0;

                    for (PdfParserService.TextSection section : sections) {
                        String title = section.getTitle();
                        String content = section.getContent();

                        if (content == null || content.trim().isEmpty()) {
                            continue;
                        }

                        String embedText = (title != null ? title + "\n" : "") + content;
                        float[] embedding = ragService.embed(embedText);

                        ChunkBgeM3 chunk = ChunkBgeM3.builder()
                                .kbId(kbId)
                                .docId(documentId)
                                .content(content)
                                .metadata(null)
                                .embedding(embedding)
                                .createdAt(now)
                                .updatedAt(now)
                                .build();

                        if (chunkBgeM3Mapper.insert(chunk) > 0) {
                            chunkCount++;
                        } else {
                            log.warn("创建 chunk 失败: format={}, documentId={}", format, documentId);
                        }
                    }
                    log.info("{} 文档处理完成: documentId={}, 共生成 {} 个 chunks", format, documentId, chunkCount);
                });
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
