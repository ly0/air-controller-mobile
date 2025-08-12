# GitHub Actions 构建配置说明

## 功能说明

此 GitHub Actions 工作流程会自动构建 Android APK 文件，包含以下功能：

1. **自动构建**: 在推送到 master/main 分支、创建标签或提交 PR 时自动触发
2. **未签名 APK**: 始终构建未签名的 APK 供测试使用
3. **签名 APK**: 如果配置了签名信息，会额外构建签名的 APK
4. **自动发布**: 创建标签时自动创建 GitHub Release

## 构建触发条件

- 推送到 `master` 或 `main` 分支
- 创建以 `v` 开头的标签（如 `v1.0.0`）
- 提交 Pull Request
- 手动触发（通过 GitHub Actions 页面）

## 配置签名 APK（可选）

如果需要构建签名的 APK，请在 GitHub 仓库的 Settings -> Secrets and variables -> Actions 中添加以下密钥：

1. **KEYSTORE_BASE64**: 将你的 keystore 文件转换为 base64 编码
   ```bash
   base64 -i your-keystore.jks -o keystore_base64.txt
   # 在 Linux/Mac 上使用:
   base64 your-keystore.jks > keystore_base64.txt
   ```
   然后将 `keystore_base64.txt` 的内容复制到此密钥

2. **KEYSTORE_PASSWORD**: Keystore 的密码

3. **KEY_ALIAS**: 签名密钥的别名

4. **KEY_PASSWORD**: 签名密钥的密码

## 构建产物

### 未签名 APK
- 文件名: `app-release-unsigned.apk`
- 适用于测试环境
- 需要在设备上启用"允许安装未知来源应用"

### 签名 APK（如果配置了签名）
- 文件名: `app-release.apk`
- 可以直接安装到设备
- 适用于生产环境发布

## 下载构建产物

1. 进入 GitHub 仓库的 Actions 页面
2. 选择对应的工作流运行记录
3. 在页面底部的 Artifacts 部分下载 APK 文件

## 自动发布

当你创建一个以 `v` 开头的标签时（例如 `v2.2.1`），工作流会自动：
1. 构建 APK
2. 创建 GitHub Release
3. 将 APK 文件附加到 Release

创建标签的命令：
```bash
git tag v2.2.1
git push origin v2.2.1
```

## 故障排除

如果构建失败，请检查：
1. Gradle 配置是否正确
2. 所有依赖是否可以正常下载
3. 如果是签名构建失败，检查密钥配置是否正确

## 注意事项

- 工作流使用 JDK 11 进行构建（与 Gradle 8.0 兼容）
- 构建产物会保留 30 天
- 签名密钥信息请妥善保管，不要提交到代码仓库