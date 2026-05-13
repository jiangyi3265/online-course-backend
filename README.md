# Online Course Backend

## 项目简介

在线课程系统后端服务，基于 RuoYi/Spring Boot 改造，提供课程管理、学习进度、练习测评、错题本、AI 问答、授权激活等接口能力。该服务为管理后台和用户端提供统一业务 API。

## 技术栈

- Java 8
- Spring Boot 2.5.15
- Spring Security / JWT
- MyBatis / PageHelper / Druid
- MySQL / Redis
- Maven
- RuoYi 3.9.1

## 关联仓库

| 子项目 | GitHub 仓库 | 说明 |
| --- | --- | --- |
| 后端服务 | [online-course-backend](https://github.com/jiangyi3265/online-course-backend) | 当前仓库，提供课程系统业务接口与后台基础能力 |
| 管理后台 | [online-course-admin](https://github.com/jiangyi3265/online-course-admin) | Vue3 管理后台，调用后端管理接口 |
| 用户端 | [online-course-app](https://github.com/jiangyi3265/online-course-app) | uni-app 用户端，调用课程学习相关接口 |

## 快速启动

```powershell
# 1. 创建 MySQL 数据库，例如 ha，并导入 sql/ry_20250522.sql
# 2. 按本机环境修改 ruoyi-admin/src/main/resources/application-druid.yml
# 3. 启动 Redis

mvn clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
```

默认后端端口为 `8007`。如需调整端口或数据库连接，请修改 `ruoyi-admin/src/main/resources/application.yml` 和 `application-druid.yml`。

## 简历描述示例

基于 RuoYi/Spring Boot 搭建在线课程平台后端，完成课程、学习进度、练习测评、错题本、授权激活等核心模块接口，并支撑管理后台与用户端协同调用。
