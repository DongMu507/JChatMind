package com.kama.jchatmind.service;

import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.entity.ChatMessage;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 多层次记忆压缩服务 (Memory Management Service)
 * 核心逻辑：
 * 1. 短期记忆 (Short-term Memory)：保留最近 N 轮对话。
 * 2. 长期记忆 (Long-term Memory)：将更早之前的历史记录交给小参数量、低成本的 LLM（如 glm-3-turbo / gpt-3.5-turbo）提炼为 Summary。
 * 3. 组装上下文：System Prompt + Long-term Summary + Short-term Memory -> 送给大模型。
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final String SUMMARY_PREFIX = "[MEMORY_SUMMARY]\\n";

    private final Map<String, ChatClient> chatClients;
    private final ChatMessageMapper chatMessageMapper;

    @Value("${llm.memory.summary-chat-client-bean:glm-4.6}")
    private String summaryChatClientBean;

    @Value("${llm.memory.fallback-chat-client-bean:}")
    private String fallbackSummaryChatClientBean;

    public ChatMemoryService(Map<String, ChatClient> chatClients, ChatMessageMapper chatMessageMapper) {
        this.chatClients = chatClients;
        this.chatMessageMapper = chatMessageMapper;
    }

    // 触发压缩的阈值（如超过 10 条消息就开始压缩历史）
    private static final int COMPRESSION_THRESHOLD = 10;
    // 压缩后，仍然保留原样作为短期记忆的条数（滑动窗口 N）
    private static final int RETAIN_RECENT_COUNT = 4;
    // 同一会话最小压缩间隔（毫秒），防止高频重复压缩。
    private static final long MIN_COMPRESSION_INTERVAL_MS = 30_000L;

    private final Set<String> sessionsInCompression = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> sessionLastCompressedAt = new ConcurrentHashMap<>();

    /**
     * 获取大模型当前可用的记忆上下文（由当前尚未压缩的消息和可能存在的 Summary 组成）
     */
    public List<ChatMessage> getEffectiveContext(String sessionId) {
        List<ChatMessage> activeMessages = fetchActiveMessagesFromDb(sessionId);
        if (activeMessages.isEmpty()) {
            return activeMessages;
        }

        ChatMessage existingSummary = activeMessages.stream()
                .filter(this::isSummaryMessage)
                .findFirst()
                .orElse(null);

        LocalDateTime summaryUpdatedAt = existingSummary != null ? existingSummary.getUpdatedAt() : null;
        List<ChatMessage> postSummaryMessages = activeMessages.stream()
                .filter(m -> !isSummaryMessage(m))
                .filter(m -> summaryUpdatedAt == null || m.getCreatedAt().isAfter(summaryUpdatedAt))
                .collect(Collectors.toList());

        int from = Math.max(0, postSummaryMessages.size() - RETAIN_RECENT_COUNT);
        List<ChatMessage> recent = postSummaryMessages.subList(from, postSummaryMessages.size());

        if (existingSummary == null) {
            return recent;
        }

        List<ChatMessage> effective = new java.util.ArrayList<>();
        effective.add(existingSummary);
        effective.addAll(recent);
        return effective;
    }

    /**
     * 核心方法（异步）：判断是否需要执行历史记录压缩
     * 这个方法通常在用户发送新消息存入数据库后调用，不会阻塞主线程！
     */
    @Async("taskExecutor")
    @Observed(name = "memory.compress", contextualName = "memory-compress")
    public void triggerMemoryCompressionAsync(String sessionId) {
        if (!sessionsInCompression.add(sessionId)) {
            log.debug("Session [{}] 记忆压缩任务已在执行中，跳过重复触发。", sessionId);
            return;
        }

        try {
            Long lastCompressedAt = sessionLastCompressedAt.get(sessionId);
            long nowMs = System.currentTimeMillis();
            if (lastCompressedAt != null && nowMs - lastCompressedAt < MIN_COMPRESSION_INTERVAL_MS) {
                log.debug("Session [{}] 距离上次压缩不足 {} ms，跳过本次触发。", sessionId, MIN_COMPRESSION_INTERVAL_MS);
                return;
            }

        List<ChatMessage> activeMessages = fetchActiveMessagesFromDb(sessionId);
        if (activeMessages.isEmpty()) {
            return;
        }

        ChatMessage existingSummary = activeMessages.stream()
            .filter(this::isSummaryMessage)
            .findFirst()
            .orElse(null);

        LocalDateTime summaryUpdatedAt = existingSummary != null ? existingSummary.getUpdatedAt() : null;
        List<ChatMessage> normalMessages = activeMessages.stream()
            .filter(m -> !isSummaryMessage(m))
            // 只压缩「上次摘要之后」新增的对话，避免重复压缩已归档历史
            .filter(m -> summaryUpdatedAt == null || m.getCreatedAt().isAfter(summaryUpdatedAt))
            .collect(Collectors.toList());

        if (normalMessages.size() <= COMPRESSION_THRESHOLD) {
            log.debug("Session [{}] 历史条数 {} 未达阈值，暂不触发记忆压缩。", sessionId, normalMessages.size());
            return;
        }

        log.info("Session [{}] 历史条数 {} 已达阈值，启动异步记忆压缩流水线...", sessionId, normalMessages.size());

        int splitIndex = normalMessages.size() - RETAIN_RECENT_COUNT;
        List<ChatMessage> toBeCompressedList = normalMessages.subList(0, splitIndex);
        List<String> toBeCompressedIds = toBeCompressedList.stream()
            .map(ChatMessage::getId)
                .collect(Collectors.toList());

        String previousSummaryText = existingSummary != null ? stripSummaryPrefix(existingSummary.getContent()) : "";
        String oldConversations = formatMessagesAsString(toBeCompressedList);

        // 3. 构建大模型 Summary Prompt (请求小模型进行压缩，降低 token 消耗)
        String summaryPrompt = String.format(
                "你是一个记忆整理助手。你需要把接下来的历史对话和旧的回顾整合为一段新的记忆回顾(Summary)。" +
                "保留用户提及的重要事实、偏好和对话核心论点。要尽可能简练。\n\n" +
                "【旧的记忆回顾】：%s\n\n" +
                "【新的历史对话】：\n%s\n\n" +
                "请输出覆盖以上全部信息的最新版记忆回顾：",
                previousSummaryText, oldConversations
        );

        String newSummaryText = callLLMToSummarize(summaryPrompt);

        saveOrUpdateSummary(sessionId, existingSummary, newSummaryText);
        markMessagesAsCompressed(toBeCompressedIds);
        sessionLastCompressedAt.put(sessionId, System.currentTimeMillis());

        log.info("Session [{}] 记忆压缩完成！已将 {} 条老旧对话提炼为长期记忆", sessionId, toBeCompressedList.size());
        } finally {
            sessionsInCompression.remove(sessionId);
        }
    }

    private List<ChatMessage> fetchActiveMessagesFromDb(String sessionId) {
        return chatMessageMapper.selectBySessionId(sessionId);
    }

    private String formatMessagesAsString(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole()) || "system".equals(msg.getRole())) {
                sb.append(msg.getRole().toUpperCase()).append(": ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    private String callLLMToSummarize(String prompt) {
        String summary = trySummarizeWithClient(summaryChatClientBean, prompt, false);
        if (summary != null) {
            return summary;
        }

        if (fallbackSummaryChatClientBean != null
                && !fallbackSummaryChatClientBean.isBlank()
                && !fallbackSummaryChatClientBean.equals(summaryChatClientBean)) {
            summary = trySummarizeWithClient(fallbackSummaryChatClientBean, prompt, true);
            if (summary != null) {
                return summary;
            }
        }

        return fallbackSummaryText();
    }

    private String trySummarizeWithClient(String beanName, String prompt, boolean fallback) {
        ChatClient summaryClient = chatClients.get(beanName);
        if (summaryClient == null) {
            if (fallback) {
                log.warn("未找到备用记忆总结 ChatClient Bean: {}，继续回退", beanName);
            } else {
                log.warn("未找到记忆总结 ChatClient Bean: {}，尝试备用模型或模拟摘要", beanName);
            }
            return null;
        }

        try {
            String summary = summaryClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                log.warn("{}记忆总结模型返回空内容，继续回退", fallback ? "备用" : "");
                return null;
            }
            if (fallback) {
                log.info("记忆总结主模型不可用，已使用备用模型: {}", beanName);
            }
            return summary;
        } catch (Exception e) {
            log.warn("{}记忆总结模型调用失败: {}，继续回退", fallback ? "备用" : "主", beanName, e);
            return null;
        }
    }

    private String fallbackSummaryText() {
        log.warn("记忆总结模型全部不可用，回退为模拟摘要");
        return "用户关注点与上下文已归纳，请继续围绕最近需求回答。";
    }

    private void saveOrUpdateSummary(String sessionId, ChatMessage existingSummary, String newSummaryText) {
        String content = SUMMARY_PREFIX + newSummaryText;
        if (existingSummary != null) {
            existingSummary.setContent(content);
            existingSummary.setUpdatedAt(LocalDateTime.now());
            chatMessageMapper.updateById(existingSummary);
            return;
        }

        ChatMessage summaryMessage = ChatMessage.builder()
                .sessionId(sessionId)
                .role("system")
                .content(content)
                .metadata(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        chatMessageMapper.insert(summaryMessage);
    }

    private void markMessagesAsCompressed(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        // 业务要求：保留原始历史消息，不做物理删除。
        log.debug("保留 {} 条已压缩历史消息，仅通过 summary.updatedAt 作为压缩边界。", ids.size());
    }

    private boolean isSummaryMessage(ChatMessage message) {
        return "system".equals(message.getRole())
                && message.getContent() != null
                && message.getContent().startsWith(SUMMARY_PREFIX);
    }

    private String stripSummaryPrefix(String summaryContent) {
        if (summaryContent == null) {
            return "";
        }
        if (summaryContent.startsWith(SUMMARY_PREFIX)) {
            return summaryContent.substring(SUMMARY_PREFIX.length());
        }
        return summaryContent;
    }
}
