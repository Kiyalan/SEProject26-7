import json
from pathlib import Path

p = Path(r"C:\Users\onayi\Desktop\小学期项目\SEProject26-7\contract\openapi.json")
doc = json.loads(p.read_text(encoding="utf-8"))
doc["info"]["version"] = "4.2.0"
tags = doc.setdefault("tags", [])
if not any(t.get("name") == "Admin" for t in tags):
    tags.append({"name": "Admin", "description": "Platform admin console (ops logs, FAQ export, integrity)"})

ref = lambda name: {"$ref": f"#/components/schemas/{name}"}
unauthorized = {"$ref": "#/components/responses/Unauthorized"}

admin_paths = {
    "/api/admin/login": {
        "post": {
            "tags": ["Admin"],
            "summary": "Admin login",
            "operationId": "adminLogin",
            "requestBody": {
                "required": True,
                "content": {"application/json": {"schema": ref("AdminLoginRequest")}},
            },
            "responses": {
                "200": {
                    "description": "Admin session",
                    "content": {"application/json": {"schema": ref("AdminLoginResponse")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/overview": {
        "get": {
            "tags": ["Admin"],
            "summary": "Admin dashboard overview",
            "operationId": "fetchAdminOverview",
            "security": [{"bearerAuth": []}],
            "responses": {
                "200": {
                    "description": "Overview",
                    "content": {"application/json": {"schema": ref("AdminOverview")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/sync-tasks": {
        "get": {
            "tags": ["Admin"],
            "summary": "List knowledge sync/build tasks across repos",
            "operationId": "fetchAdminSyncTasks",
            "security": [{"bearerAuth": []}],
            "parameters": [
                {"name": "status", "in": "query", "schema": {"type": "string", "default": "all"}},
                {"name": "keyword", "in": "query", "schema": {"type": "string"}},
                {
                    "name": "limit",
                    "in": "query",
                    "schema": {"type": "integer", "default": 50, "minimum": 1, "maximum": 200},
                },
            ],
            "responses": {
                "200": {
                    "description": "Sync tasks",
                    "content": {"application/json": {"schema": ref("AdminSyncTaskList")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/sync-failures": {
        "get": {
            "tags": ["Admin"],
            "summary": "List failed sync/build tasks",
            "operationId": "fetchAdminSyncFailures",
            "security": [{"bearerAuth": []}],
            "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer", "default": 50}}],
            "responses": {
                "200": {
                    "description": "Failures",
                    "content": {"application/json": {"schema": ref("AdminSyncFailureList")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/integrity": {
        "get": {
            "tags": ["Admin"],
            "summary": "Per-repo knowledge/FAQ integrity snapshot",
            "operationId": "fetchAdminIntegrity",
            "security": [{"bearerAuth": []}],
            "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer", "default": 100}}],
            "responses": {
                "200": {
                    "description": "Integrity checks",
                    "content": {"application/json": {"schema": ref("AdminIntegrityList")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/faq/repos": {
        "get": {
            "tags": ["Admin"],
            "summary": "List repos with FAQ counts for export",
            "operationId": "fetchAdminFaqRepos",
            "security": [{"bearerAuth": []}],
            "responses": {
                "200": {
                    "description": "FAQ repo options",
                    "content": {"application/json": {"schema": ref("AdminFaqRepoList")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/faq/export": {
        "post": {
            "tags": ["Admin"],
            "summary": "Batch export FAQ for selected repos",
            "operationId": "exportAdminFaq",
            "security": [{"bearerAuth": []}],
            "requestBody": {
                "required": True,
                "content": {"application/json": {"schema": ref("AdminFaqExportRequest")}},
            },
            "responses": {
                "200": {
                    "description": "Exported document",
                    "content": {"application/json": {"schema": ref("AdminFaqExportResponse")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/audit-logs": {
        "get": {
            "tags": ["Admin"],
            "summary": "List admin audit logs",
            "operationId": "fetchAdminAuditLogs",
            "security": [{"bearerAuth": []}],
            "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer", "default": 100}}],
            "responses": {
                "200": {
                    "description": "Audit logs",
                    "content": {"application/json": {"schema": ref("AdminAuditLogList")}},
                },
                "401": unauthorized,
            },
        }
    },
    "/api/admin/users": {
        "get": {
            "tags": ["Admin"],
            "summary": "List community users (may be empty)",
            "operationId": "fetchAdminUsers",
            "security": [{"bearerAuth": []}],
            "responses": {
                "200": {
                    "description": "Users",
                    "content": {"application/json": {"schema": ref("AdminUserList")}},
                },
                "401": unauthorized,
            },
        }
    },
}

doc["paths"].update(admin_paths)
schemas = doc["components"]["schemas"]
schemas.update(
    {
        "AdminLoginRequest": {
            "type": "object",
            "required": ["username", "password"],
            "properties": {"username": {"type": "string"}, "password": {"type": "string"}},
        },
        "AdminLoginResponse": {
            "type": "object",
            "required": ["token", "username", "role"],
            "properties": {
                "token": {"type": "string"},
                "username": {"type": "string"},
                "role": {"type": "string"},
            },
        },
        "AdminPlatformStats": {
            "type": "object",
            "required": [
                "totalRepos",
                "syncedRepos",
                "failedRepos",
                "knowledgeChunks",
                "memoryEntries",
                "faqEntries",
                "activeUsers",
                "openIssues",
                "syncSuccessRate",
                "lastFullCheck",
            ],
            "properties": {
                "totalRepos": {"type": "integer"},
                "syncedRepos": {"type": "integer"},
                "failedRepos": {"type": "integer"},
                "knowledgeChunks": {"type": "integer"},
                "memoryEntries": {"type": "integer"},
                "faqEntries": {"type": "integer"},
                "activeUsers": {"type": "integer"},
                "openIssues": {"type": "integer"},
                "syncSuccessRate": {"type": "number"},
                "lastFullCheck": {"type": "string"},
            },
        },
        "AdminHealthTrendPoint": {
            "type": "object",
            "required": ["date", "success", "failed"],
            "properties": {
                "date": {"type": "string"},
                "success": {"type": "integer"},
                "failed": {"type": "integer"},
            },
        },
        "AdminSyncTask": {
            "type": "object",
            "required": ["id", "repoFullName", "status", "startedAt", "filesSynced", "trigger"],
            "properties": {
                "id": {"type": "string"},
                "repoId": {"type": "string"},
                "repoFullName": {"type": "string"},
                "owner": {"type": "string"},
                "status": {"type": "string", "enum": ["success", "running", "failed", "paused"]},
                "startedAt": {"type": "string"},
                "endedAt": {"type": ["string", "null"]},
                "filesSynced": {"type": "integer"},
                "errorMessage": {"type": ["string", "null"]},
                "trigger": {"type": "string", "enum": ["manual", "webhook", "scheduled"]},
            },
        },
        "AdminOverview": {
            "type": "object",
            "required": ["stats", "healthTrend", "recentSyncTasks"],
            "properties": {
                "stats": ref("AdminPlatformStats"),
                "healthTrend": {"type": "array", "items": ref("AdminHealthTrendPoint")},
                "recentSyncTasks": {"type": "array", "items": ref("AdminSyncTask")},
            },
        },
        "AdminSyncTaskList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminSyncTask")},
                "total": {"type": "integer"},
            },
        },
        "AdminSyncFailure": {
            "type": "object",
            "required": [
                "id",
                "repoFullName",
                "failedAt",
                "errorType",
                "errorMessage",
                "retryCount",
                "status",
            ],
            "properties": {
                "id": {"type": "string"},
                "repoFullName": {"type": "string"},
                "failedAt": {"type": "string"},
                "errorType": {
                    "type": "string",
                    "enum": ["network", "auth", "rate_limit", "webhook", "parse"],
                },
                "errorMessage": {"type": "string"},
                "retryCount": {"type": "integer"},
                "status": {"type": "string", "enum": ["pending", "retrying", "ignored"]},
            },
        },
        "AdminSyncFailureList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminSyncFailure")},
                "total": {"type": "integer"},
            },
        },
        "AdminIntegrityCheck": {
            "type": "object",
            "required": [
                "repoFullName",
                "knowledgeOk",
                "memoryOk",
                "faqOk",
                "chunkCount",
                "memoryCount",
                "lastChecked",
                "issues",
            ],
            "properties": {
                "repoId": {"type": "string"},
                "repoFullName": {"type": "string"},
                "knowledgeOk": {"type": "boolean"},
                "memoryOk": {"type": "boolean"},
                "faqOk": {"type": "boolean"},
                "chunkCount": {"type": "integer"},
                "memoryCount": {"type": "integer"},
                "lastChecked": {"type": "string"},
                "issues": {"type": "array", "items": {"type": "string"}},
            },
        },
        "AdminIntegrityList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminIntegrityCheck")},
                "total": {"type": "integer"},
            },
        },
        "AdminFaqRepoOption": {
            "type": "object",
            "required": ["repoId", "repoFullName", "faqCount", "memoryCount", "lastUpdated"],
            "properties": {
                "repoId": {"type": "string"},
                "repoFullName": {"type": "string"},
                "faqCount": {"type": "integer"},
                "memoryCount": {"type": "integer"},
                "lastUpdated": {"type": "string"},
            },
        },
        "AdminFaqRepoList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminFaqRepoOption")},
                "total": {"type": "integer"},
            },
        },
        "AdminFaqExportRequest": {
            "type": "object",
            "required": ["repoIds"],
            "properties": {
                "repoIds": {"type": "array", "items": {"type": "string"}},
                "format": {"type": "string", "enum": ["markdown", "json"], "default": "markdown"},
            },
        },
        "AdminFaqExportResponse": {
            "type": "object",
            "required": ["format", "content", "itemCount", "repoCount", "exportedAt"],
            "properties": {
                "format": {"type": "string", "enum": ["markdown", "json"]},
                "content": {"type": "string"},
                "itemCount": {"type": "integer"},
                "repoCount": {"type": "integer"},
                "exportedAt": {"type": "string"},
            },
        },
        "AdminAuditLog": {
            "type": "object",
            "required": ["id", "admin", "action", "target", "result", "createdAt"],
            "properties": {
                "id": {"type": "string"},
                "admin": {"type": "string"},
                "action": {"type": "string"},
                "target": {"type": "string"},
                "result": {"type": "string", "enum": ["success", "failed"]},
                "createdAt": {"type": "string"},
            },
        },
        "AdminAuditLogList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminAuditLog")},
                "total": {"type": "integer"},
            },
        },
        "AdminCommunityUser": {
            "type": "object",
            "required": ["id", "login", "email", "boundRepos", "status", "lastLogin", "createdAt"],
            "properties": {
                "id": {"type": "string"},
                "login": {"type": "string"},
                "email": {"type": "string"},
                "boundRepos": {"type": "integer"},
                "status": {"type": "string", "enum": ["active", "suspended"]},
                "lastLogin": {"type": "string"},
                "createdAt": {"type": "string"},
            },
        },
        "AdminUserList": {
            "type": "object",
            "required": ["items", "total"],
            "properties": {
                "items": {"type": "array", "items": ref("AdminCommunityUser")},
                "total": {"type": "integer"},
                "message": {"type": "string"},
            },
        },
    }
)

p.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("ok", doc["info"]["version"], len(admin_paths))
