# API 实现索引

所有路径以 `/api/v1` 为前缀，认证使用 Bearer 令牌。写请求除搜索外传入 `Idempotency-Key`。

| 方法 | 路径 |
| --- | --- |
| GET | `/demo/accounts` |
| POST | `/demo/sessions` |
| GET | `/me` |
| GET | `/activity-categories` |
| GET | `/activities` |
| GET | `/activities/{id}` |
| POST | `/activities/{id}/registrations` |
| GET | `/me/registrations` |
| GET | `/me/registrations/{id}` |
| PUT | `/me/registrations/{id}/cancellation` |
| GET | `/me/registrations/{id}/check-in` |
| GET | `/management/clubs/{club}/activities` |
| POST | `/management/clubs/{club}/activities` |
| GET | `/management/activities/{id}` |
| PATCH | `/management/activities/{id}` |
| POST | `/management/activities/{id}/submissions` |
| GET | `/management/activity-submissions` |
| GET | `/management/activities/{id}/submissions` |
| GET | `/management/activities/{id}/submissions/{sub}` |
| PUT | `/management/activities/{id}/submissions/{sub}/review` |
| PUT | `/management/activities/{id}/cancellation` |
| GET | `/management/activities/{id}/registrations` |
| POST | `/management/activities/{id}/registration-searches` |
| PUT | `/management/activities/{id}/registrations/{reg}/check-in` |
| GET | `/management/accounts` |
| GET | `/management/accounts/{account}/role-grants` |
| GET | `/management/accounts/{account}/role-grants/{grant}` |
| POST | `/management/accounts/{account}/role-grants` |
| DELETE | `/management/accounts/{account}/role-grants/{grant}` |

请求与返回字段见控制器 `Api.java`、业务服务 `Activities.java` 与前端调用。GET 列表默认返回 20 条，使用 `page` 和 `pageSize` 翻页。
