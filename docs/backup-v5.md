# Eta 完整备份 v5

## 格式

`.eta-backup` 是版本化的完整归档。未加密时，文件内容是一个 ZIP；设置密码后，整个 ZIP 由流式 AES-256-GCM 容器加密。导出界面允许留空密码，以保持旧设备和离线工具的兼容性。

归档包含：

- `eta-backup.json`：旧版逻辑备份合同，用于兼容、统计和校验。
- `data/databases/`：`eta.db`、会议、会话扩展、学习、调度、A2A、通知历史及运行时检查点。
- `data/shared_prefs/` 与 `data/files/datastore/`：设置、MCP/A2A/语音 Token 的 Keystore 密文和 DataStore。
- `data/no_backup/provider_credentials/`：模型账号的 Keystore 密文。
- `data/files/`：聊天/会议产物、Skill、MCP 文件和工作区文件。
- `manifest.json`：格式版本、导出时间、应用版本、根目录状态、分区文件数、记录数、字节数和哈希。

`manifest.json` 中始终存在以下分区，即使当前设备没有对应数据：`core`、`runtime`、`meeting`、`meeting_artifacts`、`conversation_extensions`、`learning`、`tasks`、`a2a`、`notifications`、`mcp_skills`、`settings`、`secrets`、`workspace`。每个分区记录 `present`、`entryCount`、`files`、`bytes`、`recordCount` 和分区哈希；每个归档文件单独记录 SHA-256。

## 导入

旧版 JSON 仍走原有事务导入。完整归档会依次执行：

1. 解压到临时目录，并限制条目数、单文件大小和解压总量。
2. 拒绝绝对路径、`..`、重复条目、清单外文件和损坏哈希。
3. 校验逻辑 JSON、数据库文件、分区计数和整体清单。
4. 在当前数据目录之外创建导入前完整安全快照。
5. 把校验后的数据放入独立暂存目录；当前 Room 和 DataStore 保持不动。
6. 用户重启 Eta 后，在 Application 初始化数据库和 DataStore 之前原子替换数据目录。
7. 替换过程中写入恢复状态；异常或进程中断会从旧目录快照回滚，部分目录尚未移动时不会被删除。

完整归档恢复需要重启是刻意设计：运行中的 SQLite、WAL、DataStore 缓存和 Keystore 存储不能被可靠地原地替换。

## 凭据迁移

模型 OAuth、MCP、A2A 和语音凭据的原始 Keystore 密文会进入归档，回滚快照也包含这些密文。Android Keystore 的非导出硬件密钥无法随文件迁移，因此导出时还会生成一份可迁移凭据载荷：它只存在于备份 ZIP 内，不会从正在使用的数据目录扫描到。完整归档恢复后，Eta 在首次启动时用目标设备的 Keystore 重新加密这些凭据，成功后删除临时载荷。

迁移载荷是明文，仅受整个 `.eta-backup` 容器的保护。导出时必须设置强密码；未加密归档等同于一张完整设备数据与凭据快照，不应发送给其他人。如果旧设备上的某条凭据本身已无法解密，该条会跳过并记录不含凭据内容类型的告警，其余数据仍会完整备份。
