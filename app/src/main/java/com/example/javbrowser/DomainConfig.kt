package com.example.javbrowser

/**
 * 網域設定管理器
 * 
 * 從 AdFilterRules 讀取雲端更新的網域設定，
 * 讓 APP 不需要重新發版就能切換被 DNS 污染的網域。
 * 
 * 使用方式：
 *   val domainConfig = DomainConfig(adFilterRules)
 *   domainConfig.getMissAvBaseUrl()          // https://missav.ws/
 *   domainConfig.getMissAvSearchUrl("ABP-123") // https://missav.ws/search/ABP-123
 */
class DomainConfig(private val adFilterRules: AdFilterRules) {

    companion object {
        // 預設網域（當雲端設定讀取失敗時的 fallback）
        const val DEFAULT_MISSAV_DOMAIN = "missav.ai"
        const val DEFAULT_7MMTV_DOMAIN = "7mmtv.sx"
        const val DEFAULT_AVPLE_DOMAIN = "avple.tv"
        const val DEFAULT_WHOS_DOMAIN = "whos.tv"
        const val DEFAULT_PIGAV_DOMAIN = "pigav.ws"
        const val DEFAULT_AVTODAY_DOMAIN = "avtoday.io"
        const val DEFAULT_JAVHDPORN_DOMAIN = "javhdporn.net"
        const val DEFAULT_JAVDB_DOMAIN = "javdb.com"
        const val DEFAULT_JAVTRAILERS_DOMAIN = "javtrailers.com"
        const val DEFAULT_JAVGURU_DOMAIN = "jav.guru"
        const val DEFAULT_SUPJAV_DOMAIN = "supjav.com"
        const val DEFAULT_NETFLAV_DOMAIN = "netflav5.com"
        const val DEFAULT_SEHUATA_DOMAIN = "sehuata.com"
    }

    /**
     * 取得目前有效的 MissAV 網域（純網域，不含 https://）
     * 例如：missav.ws、missav.com
     */
    fun getMissAvDomain(): String {
        return adFilterRules.getDomains()["missav"] ?: DEFAULT_MISSAV_DOMAIN
    }

    /**
     * 取得 MissAV 首頁完整 URL
     * 例如：https://missav.ws/
     */
    fun getMissAvBaseUrl(): String = "https://${getMissAvDomain()}/"

    /**
     * 取得 MissAV 搜尋完整 URL
     * 例如：https://missav.ws/search/ABP-123
     */
    fun getMissAvSearchUrl(query: String): String =
        "https://${getMissAvDomain()}/search/${query}"

    fun getJableDomain(): String = adFilterRules.getDomains()["jable"] ?: "jable.tv"

    fun getRouVideoDomain(): String = adFilterRules.getDomains()["rou_video"] ?: "rouva3.xyz"

    fun getAvJoyDomain(): String = adFilterRules.getDomains()["avjoy"] ?: "avjoy.me"

    fun getPigAvDomain(): String = adFilterRules.getDomains()["pigav"] ?: DEFAULT_PIGAV_DOMAIN

    fun getAvTodayDomain(): String = adFilterRules.getDomains()["avtoday"] ?: DEFAULT_AVTODAY_DOMAIN

    fun getJavHdPornDomain(): String = adFilterRules.getDomains()["javhdporn"] ?: DEFAULT_JAVHDPORN_DOMAIN

    fun getJavDbDomain(): String = adFilterRules.getDomains()["javdb"] ?: DEFAULT_JAVDB_DOMAIN

    fun getJavTrailersDomain(): String = adFilterRules.getDomains()["javtrailers"] ?: DEFAULT_JAVTRAILERS_DOMAIN

    fun getJavGuruDomain(): String = adFilterRules.getDomains()["javguru"] ?: DEFAULT_JAVGURU_DOMAIN

    fun getSupJavDomain(): String = adFilterRules.getDomains()["supjav"] ?: DEFAULT_SUPJAV_DOMAIN

    fun getNetflavDomain(): String = adFilterRules.getDomains()["netflav"] ?: DEFAULT_NETFLAV_DOMAIN

    fun getSehuataDomain(): String = adFilterRules.getDomains()["sehuata"] ?: DEFAULT_SEHUATA_DOMAIN

    fun get7MmTvDomain(): String =
        adFilterRules.getDomains()["7mmtv"] ?: DEFAULT_7MMTV_DOMAIN

    fun getAvpleDomain(): String =
        adFilterRules.getDomains()["avple"] ?: DEFAULT_AVPLE_DOMAIN

    fun getWhosDomain(): String =
        adFilterRules.getDomains()["whos"] ?: DEFAULT_WHOS_DOMAIN

    fun get7MmTvBaseUrl(): String = "https://${get7MmTvDomain()}/"

    /** 7MMTV 的搜尋表單會 POST，但送出後會導向可重複使用的 GET 結果頁。 */
    fun get7MmTvSearchUrl(query: String): String =
        "${get7MmTvBaseUrl().trimEnd('/')}/zh/searchall_search/all/${android.net.Uri.encode(query)}/1.html"

    fun getAvpleBaseUrl(): String = "https://${getAvpleDomain()}/"

    fun getAvpleSearchUrl(query: String): String =
        getAvpleBaseUrl().trimEnd('/') + "/search?key=" +
            android.net.Uri.encode(query)

    fun getWhosBaseUrl(): String = "https://${getWhosDomain()}/"

    fun getWhosSearchUrl(query: String): String =
        getWhosBaseUrl().trimEnd('/') + "/result?search=" +
            android.net.Uri.encode(query)

    fun getPigAvBaseUrl(): String = "https://${getPigAvDomain()}/"

    fun getPigAvSearchUrl(query: String): String =
        getPigAvBaseUrl().trimEnd('/') + "/search?search=" +
            android.net.Uri.encode(query) + "&searchTarget=local"

    fun getAvTodayBaseUrl(): String = "https://${getAvTodayDomain()}/"

    fun getAvTodaySearchUrl(query: String): String =
        getAvTodayBaseUrl().trimEnd('/') + "/search?s=" + android.net.Uri.encode(query)

    fun getJavHdPornBaseUrl(): String = "https://www.${getJavHdPornDomain()}/"

    fun getJavHdPornSearchUrl(query: String): String =
        getJavHdPornBaseUrl() + "?s=" + android.net.Uri.encode(query)

    fun getJavDbBaseUrl(): String = "https://${getJavDbDomain()}/"

    fun getJavTrailersBaseUrl(): String = "https://${getJavTrailersDomain()}/"

    fun getJavGuruBaseUrl(): String = "https://${getJavGuruDomain()}/"

    fun getJavGuruSearchUrl(query: String): String =
        getJavGuruBaseUrl() + "?s=" + android.net.Uri.encode(query)

    fun getSupJavBaseUrl(): String = "https://${getSupJavDomain()}/"

    fun getSupJavSearchUrl(query: String): String =
        getSupJavBaseUrl() + "?s=" + android.net.Uri.encode(query)

    fun getNetflavBaseUrl(): String = "https://${getNetflavDomain()}/"

    fun getNetflavSearchUrl(query: String): String =
        getNetflavBaseUrl().trimEnd('/') + "/search?type=title&keyword=" +
            android.net.Uri.encode(query)

    fun getSehuataBaseUrl(): String = "https://${getSehuataDomain()}/"

    /**
     * 更新 URL 中的網域為最新網域 (如果是已知的被封鎖網域)
     * 主要用於：書籤載入、歷史紀錄等，確保讀取的舊網址自動替換為最新有效網域
     */
    fun updateUrlIfNeeded(url: String): String {
        try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return url

            if (host.contains("missav.", ignoreCase = true)) {
                return uri.buildUpon().authority(getMissAvDomain()).build().toString()
            } else if (host.contains("jable.", ignoreCase = true)) {
                return uri.buildUpon().authority(getJableDomain()).build().toString()
            } else if (host.contains("rou.video", ignoreCase = true) || host.contains("rouva", ignoreCase = true)) {
                return uri.buildUpon().authority(getRouVideoDomain()).build().toString()
            } else if (host.contains("avjoy.", ignoreCase = true)) {
                return uri.buildUpon().authority(getAvJoyDomain()).build().toString()
            } else if (host.contains("7mmtv", ignoreCase = true) ||
                host.contains("7tv", ignoreCase = true)) {
                return uri.buildUpon().authority(get7MmTvDomain()).build().toString()
            } else if (host.contains("avple", ignoreCase = true)) {
                return uri.buildUpon().authority(getAvpleDomain()).build().toString()
            } else if (host.contains("whos", ignoreCase = true)) {
                return uri.buildUpon().authority(getWhosDomain()).build().toString()
            } else if (host.contains("pigav", ignoreCase = true)) {
                return uri.buildUpon().authority(getPigAvDomain()).build().toString()
            } else if (host.contains("avtoday", ignoreCase = true)) {
                return uri.buildUpon().authority(getAvTodayDomain()).build().toString()
            } else if (host.contains("javhdporn", ignoreCase = true)) {
                return uri.buildUpon().authority("www.${getJavHdPornDomain()}").build().toString()
            } else if (host.contains("javdb", ignoreCase = true)) {
                return uri.buildUpon().authority(getJavDbDomain()).build().toString()
            } else if (host.contains("javtrailers", ignoreCase = true)) {
                return uri.buildUpon().authority(getJavTrailersDomain()).build().toString()
            } else if (host.contains("jav.guru", ignoreCase = true) ||
                host.contains("javguru", ignoreCase = true)) {
                return uri.buildUpon().authority(getJavGuruDomain()).build().toString()
            } else if (host.contains("supjav", ignoreCase = true)) {
                return uri.buildUpon().authority(getSupJavDomain()).build().toString()
            } else if (host.contains("netflav", ignoreCase = true)) {
                return uri.buildUpon().authority(getNetflavDomain()).build().toString()
            } else if (host.contains("sehuata", ignoreCase = true)) {
                return uri.buildUpon().authority(getSehuataDomain()).build().toString()
            }
        } catch (e: Exception) {
            // 解析失敗則直接回傳原網址
        }
        return url
    }
}
