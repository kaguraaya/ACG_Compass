package com.acgcompass.data.remote.anilist

// AniList (P1) GraphQL 数据源（RC.01 3.3/3.8/3.11，task 31.2）。
// - AniListApi：在共享 GraphQlClient（薄 OkHttp POST，公共查询免鉴权）之上执行查询并映射错误。
// - AniListDtos / AniListQueries / AniListMappers：kotlinx.serialization DTO、GraphQL 查询、DTO→领域映射。
// - AniListRemoteDataSource：search-by-title + media-by-id → 领域 Work / RatingEntry / WorkMatch。
// 接入 DataSourceOrchestrator 降级链的 P1 源（Bangumi 之后），英文标题作中文兜底（RC.01 3.11）。
// 注：沿用 TD-8.1 薄 GraphQL 调用器，未引入 Apollo codegen。
