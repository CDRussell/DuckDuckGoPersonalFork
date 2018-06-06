package com.duckduckgo.app.bookmarks.di

import com.duckduckgo.app.bookmarks.api.BookmarkSyncService
import dagger.Module
import dagger.Provides
import retrofit2.Retrofit


@Module
class BookmarkModule {

    @Provides
    fun bookmarkSyncService(retrofit: Retrofit): BookmarkSyncService {
        return retrofit.create(BookmarkSyncService::class.java)
    }
}