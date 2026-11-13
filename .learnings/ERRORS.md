# Errors

## [ERR-20261106-DOC] Document renderer availability

**Logged**: 2026-11-06
**Priority**: low
**Status**: in_progress
**Area**: docs

### Summary
Catalog skill paths were not durable for helper execution; the resolved runtime copy was usable. Canonical render_docx.py then failed because soffice.exe was unavailable, followed by sandbox temp cleanup errors.

### Suggested Fix
Resolve helper paths under the workspace dependency runtime. Use Word PDF export as an available rendering fallback and rasterize with the bundled Python runtime. Do not launch installed desktop LibreOffice.

### Resolution
- **Resolved**: 2026-11-06
- **Status**: resolved
- **Notes**: Word COM required sandbox escalation and exported the DOCX to an internal PDF. Bundled pypdfium2 rasterized every page; all four final page images were visually inspected. Removed inherited Title borders and disabled document-grid effects to obtain clean pagination.

## [ERR-20261106-MVN] Relative local repository path

**Logged**: 2026-11-06
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
An offline Maven invocation using -f from a different working directory resolved the relative local repository path incorrectly and could not find cached artifacts.

### Resolution
Used the verified absolute workspace cache path. Project startup script already constructs an absolute path and was verified with Windows PowerShell.

## 2026-11-13 外部检索

已列出的 search_service 工具实际不可用；部分医院旧地址返回 404、NHS Inform 返回 403。改为核实可访问的 AAOS 原文，不引用未能核实的页面。

## 2026-11-13 MyBatis 和构建

MyBatis 注解 Mapper 不能用同名重载区分插入与查询，已改为 addObservation / observationBySession。Windows 运行中的 jar 会锁定打包目标，先停止项目服务再重打包。Python 在中文 Windows 上读源码必须显式 encoding=utf-8。最终构建、隔离库与事务回归通过。
