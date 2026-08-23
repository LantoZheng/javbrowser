package com.example.javbrowser

data class FavoriteItem(
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    // JavDB enriched fields (null = not yet fetched)
    val javCode: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val maker: String? = null,
    val series: String? = null,
    val genres: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val isEnriched: Boolean = false,
    val addedAt: Long = 0L,  // Unix timestamp ms，0 表示舊資料（排在最後）
    val relatedUrls: Map<String, String> = emptyMap(),  // key = CrossSiteChecker.KEY_*
    val galleryImages: List<String> = emptyList(),      // JavTrailers swiper 圖片列表
    val trailerUrl: String? = null,                     // 預告片直接 MP4 連結
    val javdbUrl: String? = null,                       // 補全時實際使用的 JavDB 頁面 URL
    val customTags: List<String> = emptyList(),        // 使用者自訂分類
    val note: String = ""                              // 使用者心得 / 備註
)
