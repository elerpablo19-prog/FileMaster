package com.filemaster.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Estratégia de permissões por versão do Android:
 *
 *  - API 26-29 : WRITE_EXTERNAL_STORAGE runtime permission é suficiente para
 *    o escopo do gerenciador (armazenamento primário).
 *  - API 30+   : MANAGE_EXTERNAL_STORAGE (All files access) — não é concedida
 *    via dialog, exige redirecionamento do usuário para Settings com
 *    `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` e verificação via
 *    Environment.isExternalStorageManager().
 *
 *  O core Rust depende dessas permissões do processo para acessar os mesmos
 *  caminhos — o sistema filtra por UID, não pela linguagem da chamada.
 */
sealed interface StoragePermissionState {
    data object Granted : StoragePermissionState
    data object NeedsLegacyRuntimeGrant : StoragePermissionState
    data object NeedsAllFilesAccess : StoragePermissionState
}

class StoragePermissionHandler {

    fun currentStatus(context: Context): StoragePermissionState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (Environment.isExternalStorageManager()) {
                StoragePermissionState.Granted
            } else {
                StoragePermissionState.NeedsAllFilesAccess
            }
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            StoragePermissionState.Granted
        } else {
            StoragePermissionState.NeedsLegacyRuntimeGrant
        }
    }

    /** Intent para a tela de "All files access" deste app (API 30+). */
    fun allFilesAccessIntent(activity: Activity): Intent {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        // Fallback para fabricantes que removem o deep-link por pacote
        return if (intent.resolveActivity(activity.packageManager) != null) {
            intent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
