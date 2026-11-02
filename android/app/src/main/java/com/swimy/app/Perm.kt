package com.swimy.app

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object Perm {
    // '사용 정보 접근' 허용 여부 (열려 있는 앱 이름을 알기 위해 필요)
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
