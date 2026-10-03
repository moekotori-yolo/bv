# 构建与发版

本文档覆盖三件事：**本地怎么构建**、**CI 需要配哪些 secret**、**怎么产出 release APK**。

---

## 一、Release APK 从哪来

所有产物都由 GitHub Actions 产出，本地不需要（也不应该）做 release 签名。

| Workflow | 触发条件 | 产物 | 说明 |
| --- | --- | --- | --- |
| `ci.yml` | push 到 `master`/`develop`/`feature/**`，或 PR | 无（只跑测试与编译） | 不需要任何 secret，用来验证仓库健康度 |
| `release.yml` | 推送 `vX.Y.Z` 格式 tag | **APK + R8 mapping → GitHub Release** | 出正式包的主流程 |
| `alpha.yml` | push 到 `develop` | APK + mapping → 预发布 Release | 日常自测包 |
| `features.yml` | push 到 `feature/**`，或向主干提 PR | 仅 Actions artifact | 分支验证，不进 Release 列表 |
| `manual_release_signed.yml` | 手动触发 | APK artifact | 指定包名打包，可选签名 |
| `alpha_build_manually_without_sign.yml` | 手动触发 | APK artifact | 免签名自测包 |

### 发一个正式版

```bash
git fetch origin
git checkout master && git pull
git tag -a v0.4.0 -m "release 0.4.0"
git push origin v0.4.0
```

推完到 Actions 页面看 `Release Build`。成功后会出现一个 Release，附带：

- `BV_<code>_<name>.release_default_universal.apk` —— 正式签名包
- `BV_<code>_<name>.debug_default_universal.apk` —— debug 包，方便对比排查
- `mapping.zip` —— R8 混淆映射，**线上崩溃栈还原的唯一依据，请妥善保存**

> 上游仓库（`aaa1115910/bv`）的 `release.yml` 里有一行 `if: github.repository == 'aaa1115910/bv'`，
> 会把 fork 的 tag 构建**整个 job 跳过**。本仓库已移除该限制。

### versionCode 从哪来

`AppConfiguration.resolveVersion()` 用 `git rev-list --count HEAD`（即提交总数）作为 versionCode。
所以 CI 必须 **完整克隆**，不能用浅克隆 —— 所有 workflow 都已设 `fetch-depth: 0`。

---

## 二、需要配置的 secret

全部在仓库 **Settings → Secrets and variables → Actions** 下添加。

### 1. `GOOGLE_SERVICES_JSON`（必需，用于 Crashlytics）

Firebase 控制台下载的 `google-services.json` **明文内容**（不是 base64）。

CI 会写入 `app/google-services.json`。未配置时构建仍会继续（`AppConfiguration.isGoogleServicesAvailable`
返回 false，Crashlytics 上传自动关闭），但会有一条 warning。

### 2. `SIGNING_PROPERTIES`（发正式版必需）

`signing.properties` 文件内容，**base64 编码后**存放。生成方式：

```bash
cat > /tmp/signing.properties <<'EOF'
keystore.path=key.jks
keystore.alias=你的别名
keystore.alias_pwd=别名密码
keystore.pwd=密钥库密码
EOF
base64 -i /tmp/signing.properties    # macOS: base64 -i /tmp/signing.properties
                                       # Linux: base64 -w0 /tmp/signing.properties
```

### 3. `SIGN_KEY`（发正式版必需）

`key.jks` 文件内容，**base64 编码后**存放：

```bash
base64 -i key.jks        # macOS
base64 -w0 key.jks       # Linux
```

三个字段必须来自**同一次** `keytool -genkeypair`。CI 会用 `keytool -list` 实际打开一次校验，
避免把损坏的 keystore 喂给 Gradle 后看到一个难懂的签名错误。

### 生成 keystore

```bash
keytool -genkeypair -v \
  -keystore key.jks \
  -alias bv \
  -keyalg RSA -keysize 2048 -validity 10000
```

### 未配置 secret 时会怎样

| 情况 | 行为 |
| --- | --- |
| 三个 secret 全缺 | 构建成功，产物为 **debug 签名**，warning 说明"不能覆盖安装正式版" |
| 只配了 `SIGN_KEY` | **失败**（无法定位 keystore） |
| 只配了 `SIGNING_PROPERTIES` | **失败** |
| 配了但 base64 解码后为空 | **失败**，并提示 secret 需要存 base64 内容 |
| `SIGN_KEY` 与 properties 不匹配 | **失败**，提示确认来自同一次 keytool |

也就是说：**没有 secret 也能跑通流水线**，只是拿到的包不能当正式版发。适合先把仓库跑起来、验证配置，再补 secret。

---

## 三、本地构建

### 前置

| 依赖 | 版本 |
| --- | --- |
| JDK | **21**（`AppConfiguration.jdk`，Gradle toolchain 强制） |
| Android SDK | compileSdk **37** |
| CMake/NDK | 仅在需要改 native 代码时 |

### 依赖

仓库用了 git 子模块，**首次克隆必须递归**：

```bash
git clone --recursive https://github.com/moekotori-yolo/bv.git
# 已经克隆过但缺子模块：
git submodule update --init --recursive
```

`libs` 子模块提供 libVLC / ffmpeg / av1 / media3Container，缺失会在链接阶段才失败。

### 命令

```bash
# 单元测试
./gradlew :app:tv:testDebugUnitTest :app:shared:testDebugUnitTest

# 免签名自测包
./gradlew assembleDefaultDebug

# 正式签名包（需要本地 signing.properties + key.jks）
./gradlew assembleDefaultRelease
```

### 包名

`applicationId` 由 `AppConfiguration.resolveApplicationId()` 决定，优先级：

1. 环境变量 `BV_APPLICATION_ID`
2. JVM 属性 `-Dbv.applicationId=...`
3. 默认 `dev.aaa1115910.bv2`

debug 构建会追加 `.debug` 后缀，所以可以和正式版共存安装。

### 本地签名

在**仓库根目录**放两个文件（均已在 `.gitignore` 中）：

- `signing.properties`（内容见上）
- `key.jks`

`app/build.gradle.kts` 通过 `signing.properties` 是否存在来决定是否启用签名 config，
所以这两个文件不存在时构建照常进行，只是产出未签名包。

---

## 四、Geetest 调试 HUD（Debug 构建）

排查 TV 端极验验证码裁切 / 手机扫码打不开时用。

**设置 → 其它 → Geetest 调试 HUD** 打开，然后触发一次真实验证。弹窗左下角会叠加：

- **视口与裁切**：`viewport(css px)`、`panel_box size`、`▶ CLIPPED 合计损失 N px`
- **弹窗 vs 屏幕**：`dialog height` / `screen height` / `▶ OVERFLOW`
- **面板类型与布局健康度**：`panel type`、`ratio h/w` 与该类型基线比对，
  偏离会提示"疑似被宿主页 CSS 改坏"
- **局域网网卡**：全部网卡的 `isUp`/地址、`LinkProperties` 视角、旧实现实际取到的 host

开关挂在全局 object 上，所以**播放器风控、短信登录、设置页 mock** 三个来源的弹窗都会生效。

只在 `BuildConfig.DEBUG` 下显示入口，不影响正式包。

---

## 五、目录速查

```
.github/
  actions/setup-build/action.yml   公共构建准备（secret 还原 / 子模块 / 缓存）
  workflows/
    ci.yml                          健康检查（无 secret）
    release.yml                     tag → Release
    alpha.yml                       develop → 预发布
    features.yml                    分支 / PR 验证
    manual_release_signed.yml       手动 release 打包
    alpha_build_manually_without_sign.yml   手动免签名打包
    auto_close_issues.yml           低质量 issue 自动关闭
    close_inactive_issues.yml       60 天无活动自动关闭

app/build/outputs/apk/default/<buildType>/
    BV_<code>_<name>.<buildType>_default_universal.apk
```

产物文件名由 `app/build.gradle.kts` 的 `androidComponents.outputFileName` 动态拼接，
版本号变化后文件名也会变 —— workflow 用 glob 匹配，不再硬编码路径。

---

## 六、改 workflow 时的一个坑

**composite action 内部不能用 `secrets` 和 `gradle` 上下文。**

`action.yml` 的 manifest 在 runner **加载阶段**就做模板求值，而这个阶段只注入了
`inputs` 和 `github`。写成：

```yaml
# 错误 —— runner 加载时就报错，job 一步都跑不起来
- name: Restore
  env:
    KEY: ${{ secrets.SIGN_KEY }}
```

会得到：

```
Unrecognized named-value: 'secrets'. Located at position 1 within expression: secrets.SIGN_KEY
```

正确做法是声明 input，由调用方 workflow 展开后传入：

```yaml
# action.yml
inputs:
  sign-key:
    description: 'key.jks 的 base64 内容'
    required: false
    default: ''
runs:
  using: composite
  steps:
    - env:
        SIGN_KEY: ${{ inputs.sign-key }}      # 这里只能用 inputs

# workflow.yml
- uses: ./.github/actions/setup-build
  with:
    sign-key: ${{ secrets.SIGN_KEY }}          # secrets 只在这里可用
```

这个错误**本地 actionlint 1.7.12 检测不到**，只有真跑 runner 才会暴露。

