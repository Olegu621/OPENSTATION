package com.openstation.launcher.market

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper

class MarketInstallReceiver : BroadcastReceiver() {

    interface Callback {
        fun onPackageInstallerResult(success: Boolean, message: String)
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile
        var activeCallback: Callback? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val rawMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Throwable) {
                        notifyResult(false, "Не удалось открыть системный установщик: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notifyResult(true, "Установка успешно завершена")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                notifyResult(false, "Установка отменена")
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                notifyResult(false, "Установка заблокирована системой")
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                notifyResult(false, "Конфликт подписи или версии с уже установленным приложением")
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                notifyResult(false, "APK несовместим с устройством")
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                notifyResult(false, "Повреждённый или некорректный APK")
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                notifyResult(false, "Недостаточно памяти для установки")
            }
            else -> {
                val msg = if (rawMsg.isNotEmpty()) "Ошибка установки ($status): $rawMsg" else "Ошибка установки ($status)"
                notifyResult(false, msg)
            }
        }
    }

    private fun notifyResult(success: Boolean, message: String) {
        val cb = activeCallback
        if (cb != null) {
            mainHandler.post {
                activeCallback?.onPackageInstallerResult(success, message)
            }
        }
    }
}
