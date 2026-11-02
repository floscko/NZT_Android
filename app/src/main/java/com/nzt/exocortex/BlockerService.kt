package com.nzt.exocortex

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class BlockerService : AccessibilityService() {

    companion object {
        init { System.loadLibrary("nzt_core") }
    }

    external fun isPackageBlockedNative(pkgName: String): Boolean

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (isPackageBlockedNative(pkg)) {
                // Мгновенно сворачиваем запрещенное приложение на домашний экран
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(
                    applicationContext,
                    "NZT SHIELD: Доступ закрыт (Идет Поток / Отдых на пике / Сон 22:00)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onInterrupt() {}
}