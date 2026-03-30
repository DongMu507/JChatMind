package com.kama.jchatmind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reranker 重排序服务
 * 职责：接收混合检索返回的 Top-K(例如 20个)，调用外部 Rerank 大模型 API 打分后，取最终的 Top-5 喂给 LLM
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    private final WebClient webClient;

    @Value("${zhipu.api.key:${spring.ai.zhipuai.api-key:YOUR_MOCK_API_KEY}}")
    private String apiKey;

    @Value("${zhipu.api.rerank-url:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
    private String rerankUrl;

    @Value("${zhipu.api.rerank-model:glm-4-reranker}")
    private String rerankModel;

    public RerankerService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * 核心 Rerank 逻辑
     * @param query 用户提问
     * @param initialChunks 混合检索出来的 Top-20 内容
     * @param topK 需要保留的最终 Top 数量（例如 5）
     * @return 格式化好的上下文 Prompt Context
     */
    @SuppressWarnings("unchecked")
    public String rerankAndFormat(String query, List<Map<String, Object>> initialChunks, int topK) {
        if (initialChunks == null || initialChunks.isEmpty()) {
            return "";
        }

        // 提取仅包含内容的列表
        List<String> contextList = initialChunks.stream()
                .map(map -> (String) map.get("content"))
                .collect(Collectors.toList());

        // 1. 组装发给 Reranker (例如 glm-4-rerank) 的 Request Body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankModel);
        requestBody.put("query", query);
        requestBody.put("documents", contextList);

        try {
            // 2. 发起 WebClient 异步调用(但这里阻塞等待同步结果，或根据 WebFlux 返回 Mono)
            Map<String, Object> response = webClient.post()
                    .uri(rerankUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            // 3. 解析排序后的结果
            // 假设智谱接口返回格式形如: { "results": [{"index": 0, "relevance_score": 0.88}, ...] }
            List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) response.get("results");

            if (rerankedResults == null) {
                log.warn("Reranker 返回异常，Fall back 到原始顺序");
                return formatChunks(contextList, topK);
            }

            // 4. 按真实相关性得分降序排列并截取截断
            rerankedResults.sort((a, b) -> {
                Double scoreA = Double.valueOf(a.get("relevance_score").toString());
                Double scoreB = Double.valueOf(b.get("relevance_score").toString());
                return scoreB.compareTo(scoreA); // 降序
            });
            
            log.info("Rerank 完成, 原文本数: {}, 取 Top K: {}", contextList.size(), topK);

            List<String> finalSortedChunks = new ArrayList<>();
            int limit = Math.min(topK, rerankedResults.size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> item = rerankedResults.get(i);
                int originalIndex = (Integer) item.get("index");
                finalSortedChunks.add(contextList.get(originalIndex));
            }

            // 5. 格式化并合并为最终的大模型 System Prompt
            return formatChunks(finalSortedChunks, topK);

        } catch (Exception e) {
            log.error("调用 Reranker 接口失败，使用原生混合检索排序降级返回", e);
            return formatChunks(contextList, topK);
        }
    }

    /**
     * 将筛选出的 chunks 拼接为一个 System Prompt 格式的字符串
     */
    private String formatChunks(List<String> chunks, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please answer the user's question base on the following context. If you don't know the answer, just say you don't know.\n\n");
        sb.append("---[Contexts Start]---\n");
        
        int count = Math.min(chunks.size(), limit);
        for (int i = 0; i < count; i++) {
            sb.append(String.format("[Context %d]:\n%s\n\n", i + 1, chunks.get(i)));
        }
        
        sb.append("---[Contexts End]---\n");
        return sb.toString();
    }
}
