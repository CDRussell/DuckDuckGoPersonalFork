/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface OfflinePageDao {

    @Query("DELETE FROM offlinePages WHERE id = :id")
    fun delete(id: Int)

    @Query("SELECT * FROM offlinePages")
    fun getAll(): LiveData<List<OfflinePageEntity>>

    @Insert
    fun insert(offlinePage: OfflinePageEntity)

    @Query("SELECT * FROM offlinePages where id = :id")
    suspend fun getOfflinePage(id: Int): OfflinePageEntity?
}


@Entity(tableName = "offlinePages")
data class OfflinePageEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val title: String?,
    val url: String,
    val filePath: String
)