package com.kama.jchatmind.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeChunkMapper {

    /**
     * 基于 pgvector 和 PostgreSQL 全文检索的 RRF 混合双路召回
     *
     * @param queryText   用户原始提问文本（用于 Full-text search）
     * @param queryVector 用户提问对应的向量（形如 "[0.012, 0.341, ...]"，用于 Vector search）
     * @param k           Top K 的数量，例如 20
     * @return 混合打分后的前 K 个结果列表
     */
    List<Map<String, Object>> hybridSearchWithRrf(
            @Param("queryText") String queryText,
            @Param("queryVector") String queryVector,
            @Param("k") int k
    );
}
