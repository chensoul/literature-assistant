# 鱼皮文献助手

一个基于 Spring Boot 3 + Vue 的现代化文献管理全栈应用，集成 AI 技术为用户提供智能文献阅读指南生成、文献管理和检索功能。

本项目 forked from [literature-assistant](https://github.com/liyupi/literature-assistant)
- okhttp-sse 分支在原来代码的基础上进行了增强：
  - 升级 Spring Boot 版本
  - 接入 OpenAI 兼容的通义千问（DashScope）模型，支持 SSE 流式生成
  - 统一注入并配置 Jackson ObjectMapper，提升 JSON 解析一致性
  - 去除 Hutool 依赖，替换为标准库与 Jackson
  - 优化长文本内存占用，生成过程按片段增量落库
  - 细化错误处理与日志输出，便于问题定位
- master 分支去掉 okhttp-sse 依赖，改为集成 spring-ai