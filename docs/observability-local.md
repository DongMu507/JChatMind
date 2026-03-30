# JChatMind 本地全链路监控

## 组成

- Spring Boot Actuator 暴露指标
- Micrometer Tracing + OpenTelemetry 导出 Trace
- Prometheus 采集指标
- Grafana 查看指标 / Trace / 日志
- Tempo 存储 Trace
- Loki + Promtail 收集本地日志
- OpenTelemetry Collector 转发 Trace

## 启动步骤

1. 启动监控栈

```bash
cd /Users/dongmu/Downloads/java项目/JChatMind-main
docker compose -f docker-compose.observability.yml up -d
```

2. 启动后端

```bash
cd /Users/dongmu/Downloads/java项目/JChatMind-main/jchatmind
./mvnw spring-boot:run
```

3. 打开页面

- Grafana: http://localhost:3000
- Prometheus: http://localhost:9090
- Tempo API: http://localhost:3200
- Loki API: http://localhost:3100

Grafana 默认账号密码:

- 用户名: `admin`
- 密码: `admin`

## 当前已接入的关键链路

- `chat.message.create`
- `chat.event.handle`
- `agent.run`
- `agent.step`
- `agent.think`
- `agent.execute`
- `agent.message.persist`
- `agent.sse.flush`
- `rag.embed.public`
- `rag.similarity.search`
- `document.upload`
- `document.parse.markdown`
- `document.parse.pdf`
- `document.parse.txt`
- `document.chunk.persist`
- `memory.compress`
- `sse.connect`
- `sse.send`

## 当前已接入的关键指标

- `/actuator/prometheus`
- `jchatmind.rag.embedding.duration`
- `jchatmind.rag.similarity.search.duration`
- `jchatmind.rag.embedding.error.count`
- `jchatmind.sse.client.active`
- `jchatmind.sse.connect.count`
- `jchatmind.sse.send.count`
- `jchatmind.sse.send.error.count`
- `jchatmind.sse.send.duration`

## 日志

应用日志输出到:

`/Users/dongmu/Downloads/java项目/JChatMind-main/jchatmind/logs/jchatmind.log`

Promtail 会把这个文件采集到 Loki。

## 停止

```bash
cd /Users/dongmu/Downloads/java项目/JChatMind-main
docker compose -f docker-compose.observability.yml down
```
