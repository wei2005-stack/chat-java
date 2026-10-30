# Java 后端（核心链路重写）

这是 `Chat` 项目的 Java 版后端，用 Spring Boot 3 重写了核心链路，前端（`../web`）与 PostgreSQL 表结构保持不变。
Go 版 `../api` 保留在仓库中作为参考实现，不再启动。

## 技术栈

- Java 17 + Spring Boot 3.2.5（spring-boot-starter-web / jdbc）
- PostgreSQL（沿用 `../api/sqlc/schema.sql` 的表结构，不需要迁移）
- JdbcTemplate 直接写 SQL（不引入 JPA / MyBatis / SQLC）
- jjwt 0.11.5（HS256）签发 access token，refresh token 放 HttpOnly Cookie
- 上游 LLM 调用使用 JDK 自带 `java.net.http.HttpClient` 消费 OpenAI 兼容的 `text/event-stream`

## 已实现的核心接口

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/login`、`POST /api/signup`、`POST /api/auth/refresh`、`POST /api/logout`、`GET /api/users/me` |
| 模型 | `GET /api/chat_model`、`GET /api/chat_model/default` |
| 工作区 | 列表 / 新建 / 详情 / 更新 / 删除、`reorder`、`set-default`、`POST /api/workspaces/default`、`POST /api/workspaces/auto-migrate`、`GET|POST /api/workspaces/{uuid}/sessions`、`GET /api/workspaces/active-sessions`、`GET|PUT /api/workspaces/{uuid}/active-session` |
| 活动会话 | `GET|PUT /api/uuid/user_active_chat_session` |
| 会 话 | `GET /api/chat_sessions/user`、`POST|PUT /api/uuid/chat_sessions`、`GET|DELETE /api/uuid/chat_sessions/{uuid}`、`PUT .../topic/{uuid}`、`PUT .../max_length/{uuid}` |
| 消息 | `GET /api/uuid/chat_messages/chat_sessions/{uuid}`、`GET|PUT|DELETE /api/uuid/chat_messages/{uuid}`、`DELETE .../chat_sessions/{uuid}` |
| 提示词 | `POST /api/chat_prompts`、`PUT|DELETE /api/uuid/chat_prompts/{uuid}` |
| 聊天 | `POST /api/chat_stream`（SSE：`delta` → `completed`，失败发 `failed`） |
| 知识库 | `GET|POST /api/knowledge/documents`、`DELETE /api/knowledge/documents/{uuid}`、`GET /api/knowledge/search?q=` |
| 其他 | `GET /api/chat_instructions`、`GET /api/health` |

未实现（投递项目里可以说明是刻意取舍）：admin 后台、快照分享、评论、TTS、`/v1` 网关与虚拟 Key、
模型权限、探索模式建议问题、标题模型生成、请求限流、文件上传。

## 运行

```bash
cd server-java
mvn package -DskipTests
java -jar target/chat-server-java-1.0.0.jar
```

服务监听 `8080`，前端 `../web` 的 dev 代理已指向 `http://localhost:8080`，启动前端前先停掉 Go 进程。

### 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://127.0.0.1:5432/chat` | 数据库连接串 |
| `PG_USER` / `PG_PASSWORD` | `postgres` / `postgres` | 数据库账号 |
| `JWT_SECRET` | 内置开发密钥 | 生产必须覆盖 |
| `DEEPSEEK_API_KEY` | 无 | 默认模型 `deepseek-chat` 的 key（`chat_model.api_auth_key` 存的是环境变量名） |

本机 Maven 若无法写入默认仓库，可用仓库内的本地配置构建：

```bash
mvn -s ../.tools/maven-settings.xml package -DskipTests
```

## 实现要点

- **鉴权**：自写 `OncePerRequestFilter`（`config/AuthFilter`）解析 `Authorization: Bearer`，不引入 Spring Security；
  密码使用 PBKDF2，与 Go 版写入的密码哈希兼容。
- **分层**：`web`（Controller + 请求/响应 record）、`service`（应用服务）、`repo`（JdbcTemplate 查询），
  简单 CRUD 不做多余的仓储抽象。
- **SSE**：Controller 直接写 `HttpServletResponse`，事件格式 `event: <type>\ndata: <json>\n\n`；
  `completed` 事件只在消息成功落库后发送，且带 `persisted=true`。
- **推理内容**：上游 `reasoning_content` 以 `<think>...</think>` 包裹后入库，历史接口按
  `reasoningContent + content` 拼接返回，和前端渲染约定一致。
- **知识库检索**：按 500 码点 / 100 重叠切块；查询侧提取中文 bigram 与 ASCII 词打分，
  单关键词命中上限 3 次、取 top5、最低分 2，不引入向量检索依赖。
