# 测试环境配置说明

## 前端测试 (Vitest)

### 已配置
- 覆盖率工具：v8
- 报告格式：text, json, html, lcov
- 报告目录：`frontend/coverage/`
- 覆盖率阈值：**90%** (statements, branches, functions, lines)

### 运行命令

```bash
# 进入前端目录
cd frontend

# 安装覆盖率依赖（首次需要）
npm install -D @vitest/coverage-v8

# 运行测试并生成覆盖率报告
npm run test:coverage

# 仅运行测试（不生成报告）
npm run test

# 监视模式运行测试
npm run test:watch
```

### 查看报告

覆盖率报告生成在 `frontend/coverage/` 目录：

| 报告类型 | 文件位置 | 说明 |
|---------|---------|------|
| 文本报告 | 控制台输出 | 终端直接查看 |
| HTML 报告 | `coverage/index.html` | 浏览器打开，可视化查看 |
| JSON 报告 | `coverage/coverage-final.json` | CI/CD 集成 |
| LCOV 报告 | `coverage/lcov.info` | Codecov 等工具使用 |

---

## 后端测试 (Maven + JaCoCo)

### 已配置
- 覆盖率插件：JaCoCo 0.8.12
- 测试报告：Surefire Report
- 报告目录：`backend/target/site/`
- 覆盖率阈值：**90%** (line, branch, class, method)

### 运行命令

```bash
# 进入后端目录
cd backend

# 运行测试并生成覆盖率报告
mvn test

# 仅运行测试
mvn surefire:test

# 生成测试报告
mvn surefire-report:report

# 跳过覆盖率检查（仅生成报告）
mvn test -Djacoco.skip=false
```

### 查看报告

| 报告类型 | 文件位置 | 说明 |
|---------|---------|------|
| 测试报告 | `target/surefire-reports/` | TestNG/JUnit 格式 |
| HTML 报告 | `target/site/surefire-report.html` | 可视化测试结果 |
| 覆盖率报告 | `target/site/jacoco/` | JaCoCo HTML 报告 |
| 覆盖率数据 | `target/jacoco.exec` | 二进制覆盖率数据 |

---

## 快速验证

### 前端验证

```bash
cd frontend
npm run test:coverage
# 检查输出中覆盖率是否 >= 90%
```

### 后端验证

```bash
cd backend
mvn test
# 检查 BUILD SUCCESS
# 查看 target/site/jacoco/index.html
```

---

## 常见问题

### Q: 前端测试失败 "Coverage threshold not met"
A: 需要补充测试用例以提升覆盖率，或临时降低阈值（在 vitest.config.ts 中修改）

### Q: 后端测试失败 "Coverage check failed"
A: JaCoCo 检查未达标，查看 `target/site/jacoco/index.html` 了解具体未覆盖代码

### Q: 如何跳过覆盖率检查？
A:
- 前端：`npm run test` (不生成报告)
- 后端：`mvn test -Djacoco.skip=true`

---

## 测试文件位置

### 前端测试
```
frontend/src/test/
├── Chat.test.tsx          # 智能问答
├── RepoList.test.tsx     # 仓库列表
├── IssueList.test.tsx    # Issue 列表
├── Login.test.tsx        # 登录
├── AdminLogin.test.tsx   # 管理员登录
├── AdminAudit.test.ts    # 审计日志
├── AuthAxios.test.ts     # 认证请求
├── Compatibility.test.ts # 兼容性测试
├── OAuthSuccess.test.tsx # OAuth 成功
└── Usability.test.ts     # 可用性测试
```

### 后端测试
```
backend/src/test/java/com/repopilot/
├── service/
│   ├── UserServiceTest.java
│   ├── FaqServiceTest.java
│   ├── KnowledgeBuildTaskServiceTest.java
│   ├── KnowledgeQueryServiceTest.java
│   ├── KnowledgeQueryServiceExtendedTest.java
│   └── GitRepositoryServiceTest.java
├── security/
│   ├── JwtUtilTest.java
│   └── SecurityTest.java
├── client/
│   └── CodeWikiClientTest.java
└── performance/
    └── PerformanceTest.java
```
