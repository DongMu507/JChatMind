package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final String embeddingModel;
    private final Timer embeddingTimer;
    private final Timer similaritySearchTimer;
    private final Counter embeddingErrorCounter;

    public RagServiceImpl(WebClient.Builder builder,
                          ChunkBgeM3Mapper chunkBgeM3Mapper,
                          MeterRegistry meterRegistry,
                          @Value("${llm.embedding.base-url:http://localhost:11434}") String ollamaBaseUrl,
                          @Value("${llm.embedding.model:bge-m3}") String embeddingModel) {
        this.webClient = builder.baseUrl(ollamaBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.embeddingModel = embeddingModel;
        this.embeddingTimer = Timer.builder("jchatmind.rag.embedding.duration")
                .description("Embedding invocation latency")
                .tag("model", embeddingModel)
                .register(meterRegistry);
        this.similaritySearchTimer = Timer.builder("jchatmind.rag.similarity.search.duration")
                .description("Knowledge base similarity search latency")
                .register(meterRegistry);
        this.embeddingErrorCounter = Counter.builder("jchatmind.rag.embedding.error.count")
                .description("Embedding invocation error count")
                .tag("model", embeddingModel)
                .register(meterRegistry);
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    @Observed(name = "rag.embed", contextualName = "rag-embed")
    private float[] doEmbed(String text) {
        Timer.Sample sample = Timer.start();
        try {
            EmbeddingResponse resp = webClient.post()
                    .uri("/api/embeddings")
                    .bodyValue(Map.of(
                            "model", embeddingModel,
                            "prompt", text
                    ))
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .block();
            Assert.notNull(resp, "Embedding response cannot be null");
            return resp.getEmbedding();
        } catch (RuntimeException e) {
            embeddingErrorCounter.increment();
            throw e;
        } finally {
            sample.stop(embeddingTimer);
        }
    }

    @Override
    @Observed(name = "rag.embed.public", contextualName = "rag-embed-public")
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    @Observed(name = "rag.similarity.search", contextualName = "rag-similarity-search")
    public List<String> similaritySearch(String kbId, String title) {
        return similaritySearchTimer.record(() -> {
            String queryEmbedding = toPgVector(doEmbed(title));
            List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
            return chunks.stream().map(ChunkBgeM3::getContent).toList();
        });
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
