package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Counter sseConnectCounter;
    private final Counter sseSendCounter;
    private final Counter sseSendErrorCounter;
    private final Timer sseSendTimer;

    public SseServiceImpl(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.sseConnectCounter = Counter.builder("jchatmind.sse.connect.count")
                .description("SSE connect count")
                .register(meterRegistry);
        this.sseSendCounter = Counter.builder("jchatmind.sse.send.count")
                .description("SSE message send count")
                .register(meterRegistry);
        this.sseSendErrorCounter = Counter.builder("jchatmind.sse.send.error.count")
                .description("SSE message send error count")
                .register(meterRegistry);
        this.sseSendTimer = Timer.builder("jchatmind.sse.send.duration")
                .description("SSE send latency")
                .register(meterRegistry);
        Gauge.builder("jchatmind.sse.client.active", clients, ConcurrentMap::size)
                .description("Current active SSE clients")
                .register(meterRegistry);
    }

    @Override
    @Observed(name = "sse.connect", contextualName = "connect-sse")
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(chatSessionId, emitter);
        sseConnectCounter.increment();

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        emitter.onCompletion(() -> {
            clients.remove(chatSessionId);
        });
        emitter.onTimeout(() -> clients.remove(chatSessionId));
        emitter.onError((error) -> clients.remove(chatSessionId));

        return emitter;
    }

    @Override
    @Observed(name = "sse.send", contextualName = "send-sse")
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter != null) {
            Timer.Sample sample = Timer.start();
            try {
                // 将消息转换为字符串
                String sseMessageStr = objectMapper.writeValueAsString(message);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(sseMessageStr)
                );
                sseSendCounter.increment();
            } catch (IOException e) {
                sseSendErrorCounter.increment();
                throw new RuntimeException(e);
            } finally {
                sample.stop(sseSendTimer);
            }
        } else {
            sseSendErrorCounter.increment();
            throw new RuntimeException("No client found for chatSessionId: " + chatSessionId);
        }
    }
}
