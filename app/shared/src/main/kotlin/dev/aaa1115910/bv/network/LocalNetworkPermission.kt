package dev.aaa1115910.bv.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 16+ (API 36) 起，`ACCESS_LOCAL_NETWORK` 是运行时危险权限。
 *
 * targetSdk >= 36 的应用若不持有它，就无法监听局域网地址 ——
 * 扫码验证页生成的 URL 里 IP 与端口都正确、手机也在同一网段，
 * 但 TV 端的服务没有真正 bind，手机会一直连不上。
 *
 * API 36 以下不存在该权限，全部方法返回"已授权"，无需任何处理。
 */
object LocalNetworkPermission {

    /** 该权限的引入版本；低于此版本直接视为已授权。 */
    private const val REQUIRED_SDK_INT = 36

    val permission: String
        get() = "android.permission.ACCESS_LOCAL_NETWORK"

    /** 当前系统是否需要这个权限。 */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= REQUIRED_SDK_INT

    fun isGranted(context: Context): Boolean {
        if (!isRequired()) return true
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 权限缺失时返回需要申请的权限数组，已授权则返回空数组。
     * 调用方可直接喂给 `ActivityResultContracts.RequestPermission`，
     * 空数组不会被 launch。
     */
    fun missingPermissions(context: Context): Array<String> =
        if (isGranted(context)) emptyArray() else arrayOf(permission)
}