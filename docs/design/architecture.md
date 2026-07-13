# System Architecture

## Overview

The system consists of the following modules:

- **Web Frontend** — User interface for repository connection, Q&A, and issue analysis
- **Backend API** — Core business logic, API routing, and service orchestration
- **GitHub Connector** — Integrates with GitHub API for repository access and issue operations
- **Knowledge Base (RAG)** — Stores and retrieves repository knowledge using vector retrieval
- **LLM Agent** — Processes natural language queries and generates responses
- **Issue Analyzer** — Detects, classifies, and auto-fixes GitHub issues

## High-Level Architecture

```text
┌──────────────┐
│ Web Frontend │
└──────┬───────┘
       │ HTTPS
       ▼
┌──────────────┐
│  Backend API │
└──────┬───────┘
       │
  ┌────┼────┐
  ▼         ▼
GitHub     RAG
API      VectorDB
  │         │
  └────┬────┘
       ▼
┌──────────────┐
│  LLM Agent   │
└──────┬───────┘
       │
  ┌────┼────┐
  ▼         ▼
 Q&A      Issue
Response  Analyzer