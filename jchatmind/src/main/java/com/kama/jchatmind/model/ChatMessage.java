package com.kama.jchatmind.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息实体类 (Mock，需根据你实际的实体类字段调整)
 */
public class ChatMessage implements Serializable {
    private Long id;
    private String sessionId;
    /** 角色：user, assistant, system, summary 等 */
    private String role;
    private String content;
    /** false 表示活跃记忆，true 表示已经被压缩成 Summary 了，不需原样带入大模型上下文 */
    private Boolean isCompressed;
    private LocalDateTime createTime;

    // 省略 Getter / Setter 及构造函数
    public ChatMessage() {}

    public ChatMessage(String sessionId, String role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.isCompressed = false;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getIsCompressed() { return isCompressed; }
    public void setIsCompressed(Boolean compressed) { isCompressed = compressed; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
