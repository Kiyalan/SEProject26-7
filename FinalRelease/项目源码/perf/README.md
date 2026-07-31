# RepoPilot 性能测试（Apache JMeter）

绕过前端 GitHub OAuth 页面，直接对 Spring Boot API（默认 `http://localhost:8000`）加压，对比：

| 仓库 | 1 虚拟用户 | 10 虚拟用户 |
|------|------------|-------------|
| [Kiyalan/TinyTestRepo](https://github.com/Kiyalan/TinyTestRepo) (`repoId=1316984338`) | ✓ | ✓ |
| [Kiyalan/SEProject26-7](https://github.com/Kiyalan/SEProject26-7) (`repoId=1291674343`) | ✓ | ✓ |

指标：

| 指标 | 含义（JMeter） |
|------|----------------|
| **平均响应时间** | `Latency` 均值（TTFB） |
| **平均周转时间** | `elapsed` 均值（发请求 → 收齐响应体） |
| **平均吞吐量** | 样本数 / 测试墙钟时长（req/s） |

每个场景还会生成 JMeter HTML Dashboard（含 **Response Times Over Time** 曲线），以及汇总对比图。

## 目录

```
perf/
  plans/repopilot-api-load.jmx   # JMeter 测试计划
  data/queries.csv               # 检索 / 问答问题池
  config/repos.properties        # 仓库 numeric id
  scripts/
    download-jmeter.ps1          # 自动下载 Apache JMeter 5.6.3
    mint-jwt.py                  # 签发与后端一致的 JWT（绕过前端登录）
    run-perf.ps1                 # 一键跑 4 组场景 + 报告
    compare-results.py           # 汇总指标与曲线图
  env.example                    # 复制为 perf/.env
  results/                       # 输出（gitignore）
```

## 前置条件

1. 后端已启动：`backend\run.ps1`（端口 8000）
2. 如测知识检索 / Chat：CodeWiki + Postgres 已起，且目标仓库已完成知识库构建
3. Python 3（标准库即可 mint JWT；画图建议 `pip install matplotlib`）
4. JDK（JMeter 需要；本机已有 Java 即可）
5. 有效 GitHub PAT（`repo` 权限）+ GitHub 用户名，用于嵌入 JWT

## 配置

```powershell
copy perf\env.example perf\.env
# 编辑 perf\.env：GITHUB_USERNAME / GITHUB_TOKEN / JWT_SECRET
```

`JWT_SECRET` 必须与 `backend/.env` 一致。若后端 `JWT_SECRET` 为空，脚本也会按空密钥（补零到 32 字节）签发，与 `JwtUtil` 行为一致。

也可跳过 mint，直接粘贴一次 OAuth 登录后拿到的 JWT：

```dotenv
JWT_TOKEN=eyJhbGciOi...
```

## 一键执行

```powershell
# 在仓库根目录
.\perf\scripts\run-perf.ps1

# 不含 Chat（更快，适合先验证管线）
.\perf\scripts\run-perf.ps1 -IncludeChat:$false

# 自定义循环次数
.\perf\scripts\run-perf.ps1 -Loops 3
```

脚本会：

1. 下载 Apache JMeter 到 `perf/tools/`（若本机未安装）
2. 签发 JWT（或使用 `JWT_TOKEN`）
3. 依次跑 4 个场景（Tiny/SE × 1vu/10vu）
4. 写出 JMeter HTML 报告 + `COMPARISON.md` + `charts/*.png`

输出示例：

```
perf/results/<runId>/
  COMPARISON.md
  summary.json
  charts/comparison.png
  charts/*-response-curve.png
  TinyTestRepo-1vu/html-report/index.html
  TinyTestRepo-10vu/html-report/index.html
  SEProject26-7-1vu/html-report/index.html
  SEProject26-7-10vu/html-report/index.html
```

## 用户旅程（每个 VU 每轮）

绕过前端，顺序调用：

1. `GET  /api/repos/{repoId}`
2. `GET  /api/repos/{repoId}/knowledge`
3. `POST /api/repos/{repoId}/knowledge/search`
4. `GET  /api/repos/{repoId}/issues`
5. `GET  /api/repos/{repoId}/knowledge/graph/status`
6. `POST /api/chat`（可用 `-IncludeChat:$false` 关闭）

## GUI 调试（可选）

```powershell
.\perf\scripts\download-jmeter.ps1
$env:JVM_ARGS="-Xms512m -Xmx2g"
& .\perf\tools\apache-jmeter-5.6.3\bin\jmeter.bat -t .\perf\plans\repopilot-api-load.jmx
```

在 GUI 中设置 User Defined Variables / 启动参数：`JWT_TOKEN`、`REPO_ID`、`THREADS`。

## 手动单场景

```powershell
$jm = .\perf\scripts\download-jmeter.ps1
$token = python .\perf\scripts\mint-jwt.py
& $jm -n -t .\perf\plans\repopilot-api-load.jmx `
  -l .\perf\results\manual.jtl -e -o .\perf\results\manual-html `
  -JPROTOCOL=http -JHOST=localhost -JPORT=8000 `
  -JJWT_TOKEN=$token `
  -JREPO_ID=1316984338 `
  -JTHREADS=10 -JRAMP_UP=5 -JLOOPS=5 `
  -JINCLUDE_CHAT=true `
  -JQUERY_CSV="$PWD\perf\data\queries.csv"
```
