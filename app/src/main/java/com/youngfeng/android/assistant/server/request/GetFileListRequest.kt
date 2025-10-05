package com.youngfeng.android.assistant.server.request

data class GetFileListRequest(
    var path: String? = null,
    var page: Int? = null,
    var pageSize: Int? = null
) : BaseRequest()
