# 参与贡献

先说清楚这个项目**做什么、不做什么**，免得白费功夫。

**欢迎的**：判定逻辑的修正与补强、真实设备上的验证报告、文档纠错、构建与测试的改进、
界面问题、以及**你自己设备上**的真机日志（这是最有价值的贡献）。

**不会被接受的**，见下面「三条红线」。它们都是刻意这么设计的，不是遗漏。

---

## 环境准备

仓库里**不含工具链**（`.toolchain/` 约 3.4GB，已在 `.gitignore` 里）。
克隆之后先按 README 的「克隆下来之后：先准备工具链」一节配好 JDK 和 Android SDK，
否则 `./build.sh` 会报 `SDK location not found`。

## 构建与测试

```bash
./build.sh                      # 编译 Debug APK
./build.sh testDebugUnitTest    # 单元测试（全是纯 JVM，不需要设备，秒级跑完）
./gradlew lintDebug             # Android Lint（当前 0 error）
```

单元测试**不需要真机也不需要模拟器**——`core/` 和 `rules/` 里的判定逻辑全是纯函数，
只接收 `NodeSnapshot` 这类纯数据，不接触 `AccessibilityNodeInfo`。改动判定逻辑时
**请一并补测试**，这是这个项目唯一可靠的回归防线。

仪器测试（`./build.sh connectedDebugAndroidTest`）需要设备。⚠ 它跑完会**卸载应用并清空数据**，
在一台已经配好的手机上跑之前先备份：

```bash
adb exec-out run-as cn.adcalm.guard cat shared_prefs/adcalm.xml > adcalm.xml.bak
adb exec-out run-as cn.adcalm.guard cat files/observer_log.jsonl > observer_log.jsonl.bak
```

## 三条红线

**1. 不要往仓库里放按厂商或按应用点名的规则清单。**
一份标注了具体广告 SDK 类名、或者从别处整编来的 viewId 清单，读起来就是一份
「针对特定厂商广告」的靶向清单——这是本项目风险最集中的地方。默认规则库因此**故意留空**。

推论：**规则必须来自你自己设备的真实数据**。不要凭猜测、也不要从别的项目（比如 GKD 的订阅）
抄 viewId——那只会制造噪声和误判。借方法可以，搬规则不行。

**2. 不要提到具体公司或广告 SDK 的名字**，代码、注释、文档、commit message 都一样。
要举例就描述特征，别写类名。

**3. 不要新增联网调用。**
整个应用目前只有 `shizuku/ShizukuInstaller.kt` 一个文件发起网络请求，且只在你主动点
「下载并安装 Shizuku」时触发。README 和免责声明对此有精确表述——
**往别处加网络调用之前，先改那段说明**，否则文档就变成假话了。

另外：**只发布源码，不发布可直接安装的 APK**。请不要提交 APK、AAB 或签名文件。

## 提交之前

- 跑一遍 `./build.sh testDebugUnitTest` 和 `./gradlew lintDebug`
- 缩进/编码跟着 `.editorconfig`，换行符由 `.gitattributes` 管（全部 LF，`.bat` 除外），不用手动改
- 改判定逻辑的话，说明**依据是什么数据**——真机日志片段最好。
  这个项目里「感觉这样更准」不算理由，之前凭感觉调过三次打分表，回头看是过拟合

## 发版

这个项目**只发布源码**，不发 APK。所谓"发版"就是给出一个可以被引用的节点：

1. `app/build.gradle.kts` 里把 `versionName` 往上走一位
2. `CHANGELOG.md` 里把 `[未发布]` 改成 `[x.y.z] - 日期`，然后在最上面新开一个空的 `[未发布]`
3. 提交，打同号数的 tag，一起推：

```bash
git tag -a v0.2.0 -m "0.2.0"
git push origin main --follow-tags
```

4. 去 GitHub 的 Releases 基于这个 tag 写说明——**直接把 CHANGELOG 里那一段贴过去**，
   别另写一份。另写的下场一定是两边慢慢对不上，而对不上的那份迟早会被当成真的。

版本号用语义化版本，但这里的"破坏性变更"要说清楚指什么：**判定逻辑的行为变了就算
minor**（用户会看到不同的点击结果），**只有内部重构和文档改动才算 patch**。
这个项目没有对外 API，所以主版本号基本不会动。

## 报告问题

用仓库的 issue 模板。**最有用的是观察日志片段**：
开「诊断模式」复现一次 → 应用内「导出日志」→ 贴相关的那几行。
没有日志的话多数问题只能靠猜。

⚠ 导出前请确认里面没有你不想公开的信息——日志含设备上的应用清单。

## 关于规则

项目支持规则，但默认一条都没有。要知道**为什么**、以及规则数据的格式，
看 `app/src/main/assets/rules/builtin.json` 里的注释——那里写了完整理由。
