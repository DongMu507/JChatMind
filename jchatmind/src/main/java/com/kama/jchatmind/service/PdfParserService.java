package com.kama.jchatmind.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * PDF/TXT 等通用文本解析服务接口
 */
public interface PdfParserService {
    /**
     * 解析 PDF 文件，提取出分块的文本
     */
    List<TextSection> parsePdf(InputStream inputStream);

    /**
     * 解析 TXT 文件，提取出分块的文本
     */
    List<TextSection> parseTxt(InputStream inputStream);

    /**
     * 文本块数据类
     */
    @Data
    @AllArgsConstructor
    @ToString
    class TextSection {
        private String title;   // 可以使用页码或段落号等作为特征标题
        private String content; // 具体分块文本
    }
}