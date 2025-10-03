package com.youngfeng.android.assistant.server.request

data class GetVideosRequest(
    var folderId: String? = null,
    var page: Int? = null,
    var pageSize: Int? = null
)
