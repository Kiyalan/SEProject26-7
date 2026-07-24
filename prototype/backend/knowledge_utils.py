import json
import re
from dataclasses import dataclass

SKIP_DIRS = {
    "node_modules",
    ".git",
    "dist",
    "build",
    ".next",
    "__pycache__",
    ".venv",
    "venv",
    "coverage",
    ".idea",
    ".vscode",
}

PRIORITY_FILENAMES = {
    "readme.md",
    "package.json",
    "requirements.txt",
    "pyproject.toml",
    "vite.config.ts",
    "vite.config.js",
    "tsconfig.json",
    "src/main.tsx",
    "src/main.ts",
    "src/app.tsx",
    "src/app.ts",
    "main.py",
    "app.py",
}

TEXT_EXTENSIONS = {
    ".md",
    ".txt",
    ".py",
    ".ts",
    ".tsx",
    ".js",
    ".jsx",
    ".json",
    ".yml",
    ".yaml",
    ".toml",
    ".html",
    ".css",
    ".scss",
    ".vue",
    ".go",
    ".rs",
    ".java",
    ".kt",
    ".sql",
    ".sh",
    ".env.example",
}

MAX_FILES = 180
MAX_FILE_BYTES = 80_000
CHUNK_SIZE = 900
CHUNK_OVERLAP = 120


@dataclass
class FileRecord:
    path: str
    file_type: str
    size: int
    language: str | None
    content: str | None = None


def should_skip_path(path: str) -> bool:
    parts = path.split("/")
    return any(part in SKIP_DIRS or part.startswith(".") for part in parts[:-1])


def is_text_file(path: str) -> bool:
    lower = path.lower()
    return any(lower.endswith(ext) for ext in TEXT_EXTENSIONS) or lower.endswith("dockerfile")


def file_priority(path: str, size: int) -> tuple[int, int, str]:
    lower = path.lower()
    name = lower.rsplit("/", 1)[-1]
    if name.startswith("readme"):
        rank = 0
    elif lower in PRIORITY_FILENAMES or name in PRIORITY_FILENAMES:
        rank = 1
    elif lower.startswith(("src/", "app/", "pages/", "components/", "backend/")):
        rank = 2
    elif lower.startswith(("docs/", "documentation/")):
        rank = 3
    else:
        rank = 4
    return (rank, size, lower)


def detect_language(path: str) -> str | None:
    mapping = {
        ".py": "Python",
        ".ts": "TypeScript",
        ".tsx": "TypeScript",
        ".js": "JavaScript",
        ".jsx": "JavaScript",
        ".md": "Markdown",
        ".json": "JSON",
        ".go": "Go",
        ".rs": "Rust",
        ".java": "Java",
        ".vue": "Vue",
        ".css": "CSS",
        ".html": "HTML",
    }
    for ext, lang in mapping.items():
        if path.lower().endswith(ext):
            return lang
    return None


def short_preview(content: str, max_len: int = 260) -> str:
    text = re.sub(r"\s+", " ", content).strip()
    return text[:max_len]


def chunk_text(content: str, file_path: str) -> list[dict]:
    lines = content.splitlines()
    chunks: list[dict] = []
    if not content.strip():
        return chunks

    start = 0
    chunk_index = 0
    while start < len(content):
        end = min(len(content), start + CHUNK_SIZE)
        piece = content[start:end]
        line_no = content[:start].count("\n") + 1
        chunks.append(
            {
                "file_path": file_path,
                "chunk_index": chunk_index,
                "content": piece,
                "start_line": line_no,
            }
        )
        chunk_index += 1
        if end >= len(content):
            break
        start = max(end - CHUNK_OVERLAP, start + 1)
    return chunks


def build_tree(paths: list[str]) -> list[dict]:
    root: dict = {}

    for path in sorted(paths):
        parts = path.split("/")
        cursor = root
        for idx, part in enumerate(parts):
            cursor.setdefault(part, {"__children__": {}, "__path__": "/".join(parts[: idx + 1])})
            if idx == len(parts) - 1:
                cursor[part]["__is_file__"] = True
            cursor = cursor[part]["__children__"]

    def to_nodes(node: dict, name: str, full_path: str) -> dict:
        is_file = node.get("__is_file__", False)
        children_dict = node.get("__children__", {})
        children = [
            to_nodes(child, child_name, child.get("__path__", f"{full_path}/{child_name}"))
            for child_name, child in children_dict.items()
        ]
        return {
            "key": full_path,
            "title": name,
            "type": "file" if is_file else ("folder" if full_path.count("/") <= 1 else "module"),
            "children": children or None,
        }

    return [to_nodes(root[name], name, name) for name in root]


def extract_modules(files: list[FileRecord]) -> list[dict]:
    buckets: dict[str, list[FileRecord]] = {}
    for file in files:
        if file.file_type != "file":
            continue
        top = file.path.split("/")[0]
        buckets.setdefault(top, []).append(file)

    modules = []
    for name, items in sorted(buckets.items()):
        langs = sorted({f.language for f in items if f.language})
        modules.append(
            {
                "name": name,
                "desc": f"包含 {len(items)} 个已索引文件",
                "files": len(items),
                "deps": langs[:5],
            }
        )
    return modules[:12]


def extract_language_stats(files: list[FileRecord]) -> dict[str, int]:
    stats: dict[str, int] = {}
    for file in files:
        if file.file_type != "file":
            continue
        lang = file.language or "Other"
        stats[lang] = stats.get(lang, 0) + 1
    return dict(sorted(stats.items(), key=lambda item: item[1], reverse=True))


def extract_repo_summary(full_name: str, file_map: dict[str, str], modules: list[dict]) -> str:
    readme = next((content for path, content in file_map.items() if path.lower().rsplit("/", 1)[-1].startswith("readme")), "")
    if readme:
        first_heading = next((line.strip("# ").strip() for line in readme.splitlines() if line.startswith("#")), "")
        preview = short_preview(readme, 360)
        return f"{full_name}：{first_heading or 'README 摘要'}。{preview}"
    if modules:
        names = "、".join(module["name"] for module in modules[:6])
        return f"{full_name} 已索引主要模块：{names}。"
    return f"{full_name} 已完成基础目录与文件索引。"


def extract_dependencies(file_map: dict[str, str]) -> list[str]:
    deps: list[str] = []
    package_json = file_map.get("package.json")
    if package_json:
        try:
            data = json.loads(package_json)
            for key in ("dependencies", "devDependencies"):
                deps.extend(data.get(key, {}).keys())
        except json.JSONDecodeError:
            pass

    requirements = file_map.get("requirements.txt") or file_map.get("backend/requirements.txt")
    if requirements:
        for line in requirements.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                deps.append(re.split(r"[<>=!]", line)[0].strip())

    return sorted(set(deps))[:20]
