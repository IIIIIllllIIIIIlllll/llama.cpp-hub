# HTTPS 自签名证书配置指南

本指南介绍如何为 llama.cpp-hub 生成自签名 HTTPS 证书并启用 HTTPS 服务。

## 为什么需要自签名证书

- 局域网环境没有公网域名
- 开发测试阶段需要加密通信
- 免费且无需第三方 CA 签发

## 前置条件

- 已安装 JDK（包含 `keytool` 工具）
- 验证 keytool 是否可用：
  ```bash
  keytool -version
  ```

## 生成自签名证书

### 1. 进入 ssl 目录

```bash
cd ssl
```

### 2. 生成自签名证书

执行以下命令生成 keystore 文件：

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg EC \
  -keysize 256 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0"
```

参数说明：

| 参数 | 说明 |
|------|------|
| `-alias llama-cpp-hub` | 证书别名 |
| `-keyalg EC` | 使用椭圆曲线算法（更轻量，推荐） |
| `-keysize 256` | 密钥长度（EC 算法 256 位等效于 RSA 3072 位） |
| `-keystore keystore.p12` | 输出的 keystore 文件名 |
| `-storetype PKCS12` | keystore 格式（PKCS12 是标准格式） |
| `-storepass changeit` | keystore 密码（请修改为自己的密码） |
| `-validity 3650` | 证书有效期（天），3650 天 = 10 年 |
| `-dname` | 证书持有者信息 |
| `-ext SAN=...` | 主题备用名称，允许 `localhost`、`127.0.0.1` 和 `0.0.0.0` 访问 |

### 3. 导出 CA 证书（用于客户端信任）

```bash
keytool -exportcert -alias llama-cpp-hub \
  -keystore keystore.p12 \
  -storepass changeit \
  -rfc \
  -file ca.crt
```

执行后会在 `ssl` 目录下生成 `ca.crt` 文件，此文件需分发给所有客户端设备。

### 4. 如果使用局域网 IP 访问

默认 SAN 只包含 `127.0.0.1` 和 `0.0.0.0`。如果你的局域网设备通过具体 IP（如 `192.168.1.100`）访问，需要生成包含该 IP 的证书：

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg EC \
  -keysize 256 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:192.168.1.100,IP:0.0.0.0"
```

然后将新生成的 `ca.crt` 重新分发给客户端。

## 启用 HTTPS

### 1. 修改配置文件

编辑 `config/application.json`，添加或修改 `https` 配置项：

```json
{
  "https": {
    "enabled": true,
    "certPath": "ssl/keystore.p12",
    "keyPath": "ssl/keystore.p12",
    "password": "changeit"
  }
}
```

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `enabled` | 是否启用 HTTPS | `false` |
| `certPath` | keystore 文件路径 | `ssl/keystore.p12` |
| `keyPath` | keystore 文件路径（PKCS12 格式与 certPath 相同） | `ssl/keystore.p12` |
| `password` | keystore 密码 | `changeit` |

### 2. 启动服务

重启 llama.cpp-hub，查看启动日志：

- **HTTPS 成功**：`HTTPS证书加载成功: ssl/keystore.p12`，访问地址显示 `https://`
- **HTTPS 失败**：`HTTPS证书加载失败: ...`，降级为 HTTP

## 配置客户端信任证书

自签名证书未被操作系统/浏览器信任，需要将 `ca.crt` 导入信任库。

### Windows - Chrome / Edge / Firefox

1. 双击 `ca.crt` 文件
2. 点击"安装证书"
3. 选择"本地计算机"，下一步
4. 选择"将所有的证书都放入下列存储"，点击"浏览"
5. 选择"受信任的根证书颁发机构"
6. 完成

> 需要管理员权限。安装后重启浏览器。

### Windows - 命令行（管理员）

```powershell
certutil -addstore -f "ROOT" ssl\ca.crt
```

### macOS - 钥匙串访问

1. 双击 `ca.crt`
2. 在钥匙串中双击该证书
3. 展开"信任"，将"使用此证书时"改为"始终信任"
4. 关闭窗口，输入密码确认

### Linux

```bash
sudo cp ca.crt /usr/local/share/ca-certificates/llama-cpp-hub.crt
sudo update-ca-certificates
```

### Java 客户端

将 `ca.crt` 导入 JDK 的 truststore：

```bash
keytool -import -trustcacerts \
  -file ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
 -alias llama-cpp-hub
```

默认 truststore 密码为 `changeit`。

### curl

```bash
curl --cacert ssl/ca.crt https://localhost:8080/api/models
```

### Python requests

```python
import requests

# 方法 1：指定 CA 文件
response = requests.get(
    "https://localhost:8080/api/models",
    verify="ssl/ca.crt"
)

# 方法 2：将 ca.crt 加入系统信任库后直接使用
response = requests.get("https://localhost:8080/api/models")
```

## SSL 目录文件说明

| 文件 | 说明 |
|------|------|
| `keystore.p12` | PKCS12 格式 keystore，包含服务器证书和私钥 |
| `ca.crt` | PEM 格式的 CA 证书，分发给客户端用于信任 |

## 常见问题

### 1. 证书密码忘了怎么办

重新生成证书即可，执行第 2 步的命令，使用新密码。

### 2. 如何更换证书密码

```bash
keytool -storepasswd -keystore keystore.p12 -storepass 旧密码 -new 新密码
```

### 3. 证书有效期到了怎么办

重新生成证书，`-validity` 参数可以设置更长的有效期。

### 4. 同时支持 HTTP 和 HTTPS

当前版本在启用 HTTPS 后会同时监听两个端口（OpenAI 端口和 Anthropic 端口）。如果只想用 HTTPS，将 `application.json` 中的 `enabled` 设为 `true` 即可。

### 5. 浏览器仍然显示不安全

- 确认已正确导入 `ca.crt` 到**受信任的根证书颁发机构**
- Windows 确认使用了管理员权限安装
- macOS 确认在钥匙串中设置为"始终信任"
- 重启浏览器

### 6. 使用 RSA 而不是 EC 算法

如果不使用椭圆曲线，可以改用 RSA：

```bash
keytool -genkeypair -alias llama-cpp-hub \
  -keyalg RSA \
  -keysize 2048 \
  -keystore keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -validity 3650 \
  -dname "CN=llama-cpp-hub, O=MyOrg, C=CN" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1,IP:0.0.0.0"
```
