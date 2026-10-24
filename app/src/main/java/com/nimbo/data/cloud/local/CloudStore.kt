package com.nimbo.data.cloud.local

import com.nimbo.data.cloud.TgFile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Índice local visto desde los repos: la impl es SQLite (CloudIndex); los
// tests usan uno en memoria. Llamadas bloqueantes (ir en IO).
interface CloudStore {
    fun upsert(fs: List<TgFile>)
    fun all(): List<TgFile>
    fun setCover(messageId: Long, hash: String)
    fun delete(messageId: Long)
    fun hashes(): Map<Long, String>
    fun clear()
    fun savePos(messageId: Long, ms: Long)
    fun positions(): Map<Long, Long>
    fun pos(messageId: Long): Long
}

@Module
@InstallIn(SingletonComponent::class)
interface StoreModule {
    @Binds
    fun store(impl: CloudIndex): CloudStore
}
