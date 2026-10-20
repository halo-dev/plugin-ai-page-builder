# 开发指南

[返回 README](../README.md)

需要 Java 21、Node.js >= 22.12.0、pnpm 12 和 Docker。以下命令均在仓库根目录执行。

```bash
# 安装前端依赖
pnpm --dir ui install --frozen-lockfile

# 启动本地 Halo（http://localhost:8090，默认 admin / admin）
bash gradlew haloServer

# 另开终端，监听并重建前端资源
pnpm --dir ui dev

# 测试
bash gradlew test -x processUiResources
pnpm --dir ui test:unit --run
pnpm --dir ui type-check

# 完整构建，JAR 输出到 build/libs/
CI=true bash gradlew build
```

修改后端 API 后，重新生成前端客户端：

```bash
bash gradlew generateApiClient -x processUiResources -x test -x :ui:check
```
