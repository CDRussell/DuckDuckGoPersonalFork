package com.duckduckgo.app.bookmarks.api

import com.duckduckgo.app.bookmarks.db.BookmarkEntity
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path


interface BookmarkSyncService {

    @GET("$BOOKMARK_SYNC_SERVER_URL/bookmarks/{key}")
    fun getBookmarks(@Path("key") key: String) : Call<BookmarkSyncResponse>

    @POST("$BOOKMARK_SYNC_SERVER_URL/bookmarks")
    fun uploadBookmarks(@Body bookmarks: BookmarksSyncUploadRequest): Call<Void>

    data class BookmarkSyncResponse(
        val bookmarks: List<BookmarkEntity>)

    data class BookmarksSyncUploadRequest(
        val bookmarks: List<BookmarkEntity>)

    companion object {
        private const val BOOKMARK_SYNC_SERVER_URL = "https://ddgbookmarks-demo.vapor.cloud"
    }
}