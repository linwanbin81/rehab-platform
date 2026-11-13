# GitHub 首次提交指南

适用目录：`E:\2025work\6-project\project1_work\rehab-platform`。

当前主项目为 0.2 摄像头观察原型，尚未初始化 Git。本指南提供手动操作步骤，没有执行初始化、提交、创建远程仓库或上传。

## 1. 创建 GitHub 空仓库

登录 [GitHub](https://github.com/)，点击右上角 `+ → New repository`。

- Repository name：例如 `rehab-training-platform`。
- Description：`基于 Vue 3、Spring Boot 与人体姿态识别的康复训练辅助平台课程原型`。
- Visibility：按课程要求选择 Public 或 Private；Private 仓库需要邀请老师后才能查看。
- 使用本指南导入本地项目时，不勾选自动添加 README、.gitignore 或 License。

创建后复制仓库 HTTPS 地址，例如：

```text
https://github.com/你的用户名/rehab-training-platform.git
```

已有空仓库可以直接使用其地址。若已有仓库包含提交，先参考第 7 节，避免直接套用空仓库步骤。

## 2. 打开正确目录

在 VS Code 打开 `rehab-platform` 文件夹，然后打开 PowerShell 终端：

```powershell
Set-Location -LiteralPath 'E:\2025work\6-project\project1_work\rehab-platform'
```

该目录包含 `README.md`、`web`、`server`、`algorithm`、`database`、`docs`、`hardware`、`tests` 和启动脚本。

上一级 `project1_work` 包含工具缓存和旧版交付存档，不作为本次仓库根目录。`交付文档/rehab-platform` 是旧版快照。

## 3. 检查忽略项

现有 `.gitignore` 已排除依赖、数据库、编译结果、日志及可重新生成的 WASM：

```gitignore
server/target/
server/data/
web/node_modules/
web/dist/
algorithm/__pycache__/
tests/__pycache__/
.runtime/
.tools/
*.log
web/public/wasm/
```

建议在首次暂存前补充环境文件和本地编辑器状态的忽略规则。下面规则可以手动追加到 `.gitignore`：

```gitignore
.env
.env.*
!.env.example
*.local
.DS_Store
Thumbs.db
```

`package-lock.json`、`pom.xml`、建表 SQL、测试、说明文档和 `.vscode/tasks.json` 应当保留提交。真实账号口令、个人训练数据和本地数据库不提交。

姿态权重文件约 9.4MB，当前没有被忽略，可以和项目一起提交；保留 `web/public/models/manifest.json` 及模型来源说明。若选择不提交权重，应先明确排除该文件，并在 README 中保留下载和摘要校验步骤，否则新电脑构建会缺少模型。

## 4. 初始化并创建第一次提交

以下命令逐行执行。将姓名和邮箱替换成自己的信息；也可使用 GitHub 邮箱设置中提供的 noreply 地址。这里配置仅对当前仓库生效。

```powershell
git init -b main
git config user.name '你的名字或GitHub用户名'
git config user.email '你的Git提交邮箱'
git add .
git status --short
git diff --cached --stat
```

检查暂存列表。应看到源码、文档、SQL 和脚本；不应看到 `node_modules`、`target`、`server/data` 或 `.runtime` 的内容。

确认后创建本地提交：

```powershell
git commit -m 'feat: initial rehabilitation training prototype'
```

若旧版 Git 不支持 `git init -b main`，改用 `git init`，并在提交后执行 `git branch -M main`。

## 5. 关联远程并上传

替换成自己实际创建的仓库地址：

```powershell
git remote add origin https://github.com/你的用户名/rehab-training-platform.git
git push -u origin main
```

如弹出 Git Credential Manager 或浏览器登录，按提示登录自己的 GitHub 账号。GitHub 不支持把账号登录密码作为 HTTPS Git 推送密码；优先使用正常的浏览器授权流程。

上传后，在 GitHub 仓库页面刷新，确认 README 和源码目录已出现。可以核对最近一次提交信息是否与本地一致：

```powershell
git log -1 --oneline
git status
git remote -v
```

`git status` 应显示没有尚未提交的修改，且分支与远程状态一致。

## 6. 为初次上传保留版本标签

当前软件版本为 0.2，因此建议使用 `v0.2.0`。首次上传仓库不要求把代码改称 0.1。

```powershell
git tag -a v0.2.0 -m 'Camera observation prototype'
git push origin v0.2.0
```

这会保留当前版本的位置。后续继续修改 main，不会改变该标签指向的初始成果。需要在 GitHub 上提供发布页时，可以再基于此标签创建 Release，说明已实现功能、运行方法和未验证范围。

## 7. 已有仓库的处理方式

若远程仓库已经有 README、License 或其他提交，建议先克隆它，然后把当前项目源码复制到克隆目录。复制时按忽略规则排除依赖、数据库和运行缓存，保留克隆目录原有 `.git`。

在克隆目录中检查差异，再执行 `git add .`、`git commit` 和 `git push`。不要直接用强制推送覆盖已有提交。

如果只是重复添加远程时出现 `remote origin already exists`，先运行 `git remote -v` 检查；确实要改地址时再使用 `git remote set-url origin 新地址`。

## 8. 后续常规更新

先检查修改，选择本次需要提交的文件，再提交和推送。例如只更新开发记录：

```powershell
git status
git add docs
git commit -m 'docs: update development notes'
git push
```

功能更新建议使用单独分支，例如 `git switch -c feat/report-export`；完成验证后再合并。每次提交描述一个清楚的改动，并同步更新测试记录和开发记录。

本项目的 GitHub README 应继续说明：当前为课程原型，摄像头主线已接入，真人识别精度、临床测量一致性、PyTorch 质量分类器和 IMU 实物联调仍有待开展。
