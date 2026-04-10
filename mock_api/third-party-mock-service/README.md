# Third Party Mock Service

基于 `public-resource-api/references/api_reference.md` 和仓库内现成的 mock 模板整理出的第三方联调 Mock 服务。

## 当前支持

已补齐用户、单位、角色、权限、字典、审批、Token 互通和 JWY 相关接口，并把 mock 数据改成了“用户 + 单位 + 关系”一套中心目录：

- 一个用户可以关联多个单位
- token 会绑定到具体用户和当前单位
- `/sys/selectDepart` 可以切换多单位用户的当前单位
- 用户信息、单位信息、列表查询、权限返回保持一致
- 其他 token 接口补了 `GET` 形式，方便第三方直接调试

完整路由见 `mock-config.json`。

## 启动

```bash
cd D:\skillDemos\third-party-mock-service
node server.js
```

默认端口是 `7700`，也可以自定义：

```bash
node server.js 3000
```

## 内置单位

- `620000040000` 模拟单位1
- `620000040100` 模拟单位1-1
- `620000040200` 模拟单位1-2
- `620000040300` 模拟单位1-3

## 内置用户

- 张三 `620102199509275310`，默认单位 `620000040000`，可切换到 `620000040100`
- 李四 `620102199812150021`，单位 `620000040100`
- 王五 `620102198807150033`，单位 `620000040200`
- 赵六 `620102199103050044`，单位 `620000040300`
- 钱七 `620102199412210055`，默认单位 `620000040200`，可切换到 `620000040000`

## Token 接口

直接获取访问 token：

GET 方式：

```bash
curl "http://localhost:7700/mock/token?username=620102199509275310&orgCode=620000040000"
curl "http://localhost:7700/mock/token?username=620102199812150021&orgCode=620000040100"
curl "http://localhost:7700/mock/token?username=620102198807150033&orgCode=620000040200"
curl "http://localhost:7700/mock/token?username=620102199103050044&orgCode=620000040300"
curl "http://localhost:7700/mock/token?username=620102199412210055&orgCode=620000040200"
```

POST 方式：

```bash
curl -X POST http://localhost:7700/mock/token ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"620102199509275310\",\"orgCode\":\"620000040000\"}"
```

登录成功后，从响应体 `result.token` 或响应头 `X-Access-Token` 里取 token。

## 其他 Token 的 GET 获取方式

子系统 token 互通：

```bash
curl "http://localhost:7700/sys/token/exchange?username=620102199509275310&orgCode=620000040000&systemCode=alarm-center"
```

JWY 应用 token：

```bash
curl "http://localhost:7700/jwy/app/token?appId=third-party-demo-app&appSecret=demo-secret"
```

JWY 用户 token：

```bash
curl "http://localhost:7700/jwy/user/token?username=620102198807150033&orgCode=620000040200&appId=third-party-demo-app"
```

## 联调示例

查询当前用户信息：

```bash
curl http://localhost:7700/sys/user/getUserInfo ^
  -H "X-Access-Token: <token>"
```

切换张三的单位到模拟单位1-1：

```bash
curl -X PUT "http://localhost:7700/sys/selectDepart?orgCode=620000040100" ^
  -H "X-Access-Token: <token>"
```

按单位查询用户：

```bash
curl "http://localhost:7700/sys/user/queryListByParam?orgCode=620000040200"
```

查询单位树：

```bash
curl "http://localhost:7700/sys/user/depTree?orgCode=620000040000"
```

## 说明

- 所有返回均为 Mock 数据。
- 无需 `npm install`。
- 修改 `mock-config.json` 或 `data/*.json` 后，服务会自动重载配置。
- 目录数据集中在 `data/mock-directory.json`，后续要继续加用户或单位，直接改这一份即可。
