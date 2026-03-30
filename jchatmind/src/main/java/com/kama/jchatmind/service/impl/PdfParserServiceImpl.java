package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.PdfParserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PdfParserServiceImpl implements PdfParserService {

    @Override
    public List<TextSection> parsePdf(InputStream inputStream) {
        List<TextSection> sections = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            int numPages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();

            // 按页进行提取分块
            for (int i = 1; i <= numPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);

                if (text != null && !text.trim().isEmpty()) {
                    // 对于长页面，如果内容过大，其实还可以进一步分块。这里为了简化直接按大块处理，目前bge-m3支持最大8192个token
                    String title = "第 " + i + " 页";
                    sections.add(new TextSection(title, text.trim()));
                }
            }
            log.info("PDF 解析完成, 共 {} 页, 生成 {} 个区块", numPages, sections.size());
            return sections;
        } catch (Exception e) {
            log.error("PDF 解析失败", e);
            throw new RuntimeException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TextSection> parseTxt(InputStream inputStream) {
        List<TextSection> sections = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder chunkBuilder = new StringBuilder();
            String line;
            int chunkIndex = 1;
            int maxCharsPerChunk = 1000; // 按固定长度（约1000字符）分块

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                chunkBuilder.append(line).append("\n");
                if (chunkBuilder.length() >= maxCharsPerChunk) {
                    sections.add(new TextSection("TXT 分块 " + chunkIndex++, chunkBuilder.toString().trim()));
                    chunkBuilder.setLength(0); // 清空
                }
            }
            // 处理剩余文本
            if (chunkBuilder.length() > 0) {
                sections.add(new TextSection("TXT 分块 " + chunkIndex, chunkBuilder.toString().trim()));
            }

            log.info("TXT 解析完成, 生成 {} 个区块", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("TXT 解析失败", e);
            throw new RuntimeException("TXT 解析失败: " + e.getMessage(), e);
        }
    }
}