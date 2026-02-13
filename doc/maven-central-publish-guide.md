# 发布到 Maven 中央仓库指南

本文档将指导你如何将 `shinyi-eventbus` 发布到 Maven 中央仓库 (Maven Central)。

## 1. 核心概念

要发布到 Maven Central，你需要满足以下条件：
*   **GroupId 所有权**：你必须证明你拥有 `pom.xml` 中定义的 `groupId`。
    *   当前配置：`com.shinyi`
    *   **注意**：除非你拥有 `shinyi.com` 域名，否则**无法注册**此 GroupId。
    *   **推荐方案**：使用 GitHub 提供的免费 GroupId：`io.github.misakiga`。
*   **元数据完整性**：POM 文件必须包含 Name, Description, URL, Licenses, Developers, SCM connection 等信息。
*   **GPG 签名**：所有发布的构件（jar, pom, javadoc, sources）都必须使用 GPG 签名。
*   **Javadoc 和 Sources**：必须提供源码包和文档包。

## 2. 账号注册与工单 (JIRA)

1.  访问 [Sonatype JIRA](https://issues.sonatype.org/) 并注册账号。
2.  创建一个新的 Issue (New Project Request):
    *   **Project**: Community Support - Open Source Project Repository Hosting (OSSRH)
    *   **Issue Type**: New Project
    *   **Summary**: Create repository for io.github.misakiga
    *   **Description**: Shinyi EventBus framework
    *   **Group Id**: `io.github.misakiga` (强烈建议修改为这个，除非你有 shinyi.com)
    *   **Project URL**: https://github.com/MISAKIGA/shinyi-eventbus
    *   **SCM URL**: https://github.com/MISAKIGA/shinyi-eventbus.git
3.  等待审核（通常需要几个小时到一天）。机器人会要求你在 GitHub 上创建一个临时仓库来验证所有权。

## 3. GPG 密钥生成

在本地机器上（Windows/Linux/Mac）：

1.  安装 GnuPG (`gpg`).
2.  生成密钥：
    ```bash
    gpg --gen-key
    # 按照提示操作，设置 passphrase (记住它！)
    ```
3.  查看密钥 ID：
    ```bash
    gpg --list-keys
    # 输出示例: pub rsa2048 2026-02-13 [SC] [expires: 2028-02-13]
    #           ABCD1234567890EF... (长ID)
    ```
4.  发布公钥到服务器：
    ```bash
    gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234567890EF...
    ```

## 4. Maven 配置 (`settings.xml`)

在你的 `~/.m2/settings.xml` 中添加认证信息：

```xml
<settings>
  <servers>
    <server>
      <id>ossrh</id>
      <username>你的JIRA用户名</username>
      <password>你的JIRA密码</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>ossrh</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <properties>
        <gpg.executable>gpg</gpg.executable>
        <gpg.passphrase>你的GPG密码</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

## 5. 项目 POM 修改

为了满足发布要求，我已经帮你修改了 `pom.xml`，增加了以下内容：
*   更正的 `groupId`: `io.github.misakiga` (假设你接受建议)
*   `name`, `description`, `url`
*   `licenses` (已存在)
*   `developers` (开发者信息)
*   `scm` (源码控制信息)
*   `distributionManagement` (发布仓库地址)
*   `maven-source-plugin`
*   `maven-javadoc-plugin`
*   `maven-gpg-plugin`
*   `nexus-staging-maven-plugin`

## 6. 执行发布

在项目根目录下运行：

```bash
mvn clean deploy -P release
```

*   `release` 是我们在 POM 中定义的 Profile，启用 GPG 签名和 Javadoc 生成。
*   如果成功，构件会被上传到 Sonatype 的 Staging Repository。
*   `nexus-staging-maven-plugin` 会自动尝试 "Close" 和 "Release" 这个 Staging Repository。
*   如果自动 Release 失败，登录 [https://s01.oss.sonatype.org/](https://s01.oss.sonatype.org/) 手动处理。

## 7. 同步到 Central

Release 成功后，通常需要 10-30 分钟同步到 Maven Central，之后你就可以在 search.maven.org 搜到了。
