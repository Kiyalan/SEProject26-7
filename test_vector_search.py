"""
向量语义检索测试脚本
用法: python test_vector_search.py
依赖: 无（纯标准库）
前提: LLM API Key 已在 backend/data/llm-config.json 或 application.yml 中配置
"""

import json, math, urllib.request, os

# ── 读取 LLM 配置（与后端一致） ──
def load_config():
    # 1. 尝试从 llm-config.json 读取
    config_path = os.path.join("backend", "data", "llm-config.json")
    if os.path.exists(config_path):
        with open(config_path, encoding="utf-8") as f:
            return json.load(f)
    # 2. 回退到环境变量
    return {
        "apiKey": os.environ.get("LLM_API_KEY", ""),
        "baseUrl": os.environ.get("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
        "embeddingModel": os.environ.get("LLM_EMBEDDING_MODEL", "openai/text-embedding-3-small"),
    }

config = load_config()
API_KEY = config.get("apiKey", "")
EMBED_MODEL = config.get("embeddingModel", "openai/text-embedding-3-small")

if not API_KEY:
    print("[ERROR] 未找到 LLM API Key。请在 backend/data/llm-config.json 中配置 apiKey。")
    exit(1)

EMBED_URL = config.get("baseUrl", "").rstrip("/") + "/embeddings"

# ── 测试数据：8 个真实代码块 + 8 个自然语言查询 ──
chunks = [
    "JWT认证过滤器：从Authorization头提取Bearer令牌，用jwtUtil解析用户名并验证，通过后设置Spring Security认证上下文。",
    "LLM答案生成：将代码库chunk上下文拼接为prompt，调用chatCompletion方法向大语言模型API发送请求并返回生成的回答。",
    "GitHub文件下载：构造GitHub API v3 URL，发送带Bearer令牌的请求获取Base64编码文件内容，解码后返回UTF-8字符串。",
    "文本分块工具：按行分割文本，使用900字符滑动窗口配合120字符重叠，将每个窗口保存为一个chunk记录（含路径、行号、索引）。",
    "JPA数据库查询：定义Spring Data JPA Repository接口，提供按commitSha查询、删除和自定义JPQL分页排序查询方法。",
    "React状态管理：使用useState管理diffs和loading状态，通过async函数调用compareKnowledgeCommits API获取两个版本diff数据。",
    "Docker容器部署：基于openjdk:21-jdk-slim镜像，复制jar包，暴露8000端口，设置prod环境变量并定义Java启动命令。",
    "代码索引构建：通过Git API选择文件列表，逐文件下载内容并SHA-256去重，调用chunkText分块后批量存入数据库。",
]

labels = [
    "JWT认证过滤器", "LLM答案生成", "GitHub文件下载", "文本分块工具",
    "JPA数据库查询", "React状态管理", "Docker部署配置", "代码索引构建"
]

queries = [
    "用户登录认证和JWT令牌验证",
    "如何调用大语言模型生成回答",
    "从GitHub下载仓库文件",
    "将长文本分割成固定大小的片段",
    "Spring Data JPA数据库查询",
    "React前端组件的状态管理",
    "Docker容器构建和部署",
    "批量构建代码索引的流程",
]

# ── 工具函数 ──
def embed(texts):
    data = json.dumps({"model": EMBED_MODEL, "input": texts}).encode()
    req = urllib.request.Request(EMBED_URL, data=data, headers={
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    })
    resp = json.loads(urllib.request.urlopen(req).read())
    return [item["embedding"] for item in resp["data"]]

def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb + 1e-10)

# ── 主流程 ──
print("=" * 75)
print(f"  向量语义检索测试  (模型: {EMBED_MODEL})")
print("=" * 75)

print(f"\n[1/3] 向量化 {len(chunks)} 个代码块...")
vecs = embed(chunks)
print(f"      完成: {len(vecs)} 个向量, 维度={len(vecs[0])}")

print(f"\n[2/3] 向量化 {len(queries)} 个查询...")
qvecs = [embed([q])[0] for q in queries]
print(f"      完成: {len(qvecs)} 个查询向量")

print("\n[3/3] 语义检索测试 (每个查询取 Top-3):\n")

vector_correct = 0
for qi, query in enumerate(queries):
    scores = [(cosine(qvecs[qi], v), i) for i, v in enumerate(vecs)]
    scores.sort(reverse=True)

    expected = labels[qi]
    top1_label = labels[scores[0][1]]
    hit = top1_label == expected

    if hit:
        vector_correct += 1
        status = "[HIT]  "
    else:
        status = "[MISS] "

    print(f"  {status} 查询: \"{query}\"")
    print(f"         期望: {expected}  |  Top1: {top1_label} ({scores[0][0]:.4f})")
    top3 = "  |  ".join(
        f"#{r+1} {labels[i]} ({s:.4f})" for r, (s, i) in enumerate(scores[:3])
    )
    print(f"         Top3: {top3}")
    print()

# ── 对比：简单关键词匹配 ──
print("-- 对比: 纯关键词匹配 (TF 风格) --")
kw_correct = 0
for qi, query in enumerate(queries):
    kw_scores = []
    for ci, chunk in enumerate(chunks):
        q_words = set(query)
        c_words = set(chunk)
        score = len(q_words & c_words) / max(len(q_words), 1)
        kw_scores.append((score, ci))
    kw_scores.sort(reverse=True)
    if labels[kw_scores[0][1]] == labels[qi]:
        kw_correct += 1

# ── 总结 ──
print(f"\n{'=' * 75}")
print(f"  关键词匹配准确率: {kw_correct}/{len(queries)} = {kw_correct/len(queries)*100:.0f}%")
print(f"  向量检索准确率:   {vector_correct}/{len(queries)} = {vector_correct/len(queries)*100:.0f}%")
print(f"{'=' * 75}")
