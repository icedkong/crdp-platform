# 演示模式（虚构数据）

`demo` 剖面和 `./scripts/dev.sh` 用来在没有华为云的机器上把系统跑起来。它**不是**生产配置。

## 加密

- 敏感字段使用已有的本地 AES-256-GCM 信封加密，算法与 `local` 剖面相同，**不是明文**。
- 主密钥来自环境变量 `CRDP_CRYPTO_LOCAL_KEY`。未设置时，`demo` 剖面会在 `data/demo-crypto.key` 生成一把并在下次启动时继续使用。`scripts/dev.sh` 默认带上与种子一致的演示钥匙 `dev-only-not-a-production-key`，这样本机库可以反复解开。
- `KmsKeyProvider` 仍保留。配置改回 `crdp.crypto.mode=kms` 才会走它。
- `prod` 剖面仍然强制 `kms`。KMS 尚未接入，因此 **prod 启动即失败**，并提示改用 `demo`。这样不会在第一次写入患者字段时才返回 501。

**接入华为云 KMS 之前，禁止导入真实患者数据。** 演示账号、种子和模拟短信只含虚构值。

## 短信

- `local` 与 `demo` 的 `crdp.sms.provider=mock`。不调用腾讯云，不把短信发到手机。
- 验证码写在后端日志（检索「模拟短信」）和页面 <http://127.0.0.1:18080/dev/mock-sms>。手机号在日志和台账里是脱敏的。
- 需要真实短信时设置 `crdp.sms.provider=tencent`，并配置 `crdp.sms.enabled=true` 与腾讯云密钥。生产剖面不要打开 mock。

## 手机扫码

二维码里的地址是 `CRDP_H5_UPLOAD_MOBILE_BASE_URL`（默认 `http://127.0.0.1:14175`）。同一台电脑浏览器用 127.0.0.1 可以打开；局域网里的手机打不开 127.0.0.1。`scripts/dev.sh` 会尽量改成这台机器的出口 IPv4。不对时手工指定，例如：

```bash
export CRDP_H5_UPLOAD_MOBILE_BASE_URL=http://192.168.1.20:14175
```

然后重启后端。手机上传页本身监听 `0.0.0.0:14175`，由开发服务器把 `/api` 转到本机 18080。
