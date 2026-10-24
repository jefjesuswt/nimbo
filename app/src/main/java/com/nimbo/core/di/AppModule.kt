package com.nimbo.core.di

import android.content.Context
import android.content.SharedPreferences
import com.nimbo.core.tdlib.TdClient
import com.nimbo.core.tdlib.TdlibClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Vive lo que vive la app: subidas, borrados diferidos, retags. Lo que se
// lanza aquí no muere al cerrar una pantalla.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    // Un solo archivo de prefs para toda la app (antes 8 accesos sueltos).
    @Provides
    @Singleton
    fun prefs(@ApplicationContext ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("nimbo", Context.MODE_PRIVATE)
}

@Module
@InstallIn(SingletonComponent::class)
interface TdModule {
    @Binds
    fun tdClient(impl: TdlibClient): TdClient
}
