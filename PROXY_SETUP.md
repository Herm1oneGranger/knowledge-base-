# Maven Proxy Setup

项目已通过 `.mvn/maven.config` 预置 Maven 代理参数，便于在受限网络环境拉取依赖。

代理信息：
- Host: `rb-proxy-unix-de01.bosch.com`
- Port: `8080`
- Username: `guz1cgd4`

> 注意：密码已写入本地开发配置文件，仅用于当前受控环境。提交到公共仓库前请改用环境变量或 CI Secret。

## 验证

```bash
mvn -v
mvn test
```
