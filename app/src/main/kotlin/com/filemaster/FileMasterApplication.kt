package com.filemaster

import android.app.Application
import com.filemaster.core.FileEngine
import com.filemaster.di.AppContainer

/**
 * Application raiz. Mantém o DI manual leve (sem Hilt/Koin — o projeto tem
 * poucas dependências e o container explícito facilita testes e previews).
 */
class FileMasterApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // O FileEngine (Rust) é um singleton: o runtime Tokio do core é criado
        // uma única vez e vive durante todo o processo.
        val engine = FileEngine()
        container = AppContainer(engine)
    }
}
