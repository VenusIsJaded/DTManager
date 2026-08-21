package com.dt.manager.ui

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dt.manager.R
import com.dt.manager.core.ApkInfo
import java.io.File

/**
 * Modal dialog showing APK metadata with three actions:
 * FUNCTION, VIEW, INSTALL — matches the MT Manager reference screenshot.
 */
object ApkInfoDialog {

    interface Listener {
        fun onView(apkFile: File)
        fun onInstall(apkFile: File)
        fun onFunctions(apkFile: File)
    }

    @JvmStatic
    fun show(activity: AppCompatActivity, apkFile: File, listener: Listener?) {
        val ctx = activity.applicationContext
        val inflater = LayoutInflater.from(activity)

        val info = ApkInfo.fromFile(ctx, apkFile)

        val content = inflater.inflate(R.layout.dialog_apk_info, null, false)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)

        val iconApp: ImageView = content.findViewById(R.id.iconApp)
        val textAppName: TextView = content.findViewById(R.id.textAppName)
        val textVersion: TextView = content.findViewById(R.id.textVersion)

        if (info.icon != null) {
            iconApp.setImageDrawable(info.icon)
        } else {
            iconApp.setImageResource(R.drawable.ic_apk)
        }
        textAppName.text = info.appName ?: apkFile.name
        textVersion.text = "Version " + (info.versionName ?: "")

        setRow(content, R.id.rowPackage, "Package name", info.packageName)
        setRow(content, R.id.rowVersionCode, "Version code", info.versionCode.toString())
        setRow(content, R.id.rowFileSize, "File size", info.formatSize())
        setRow(content, R.id.rowSignature, "Signature", info.signatureScheme)
        setRow(content, R.id.rowTargetSdk, "Target SDK", info.formatSdk())
        setRow(content, R.id.rowMinSdk, "Minimum SDK", info.formatMinSdk())

        val btnFunctions: TextView = content.findViewById(R.id.btnFunctions)
        val btnView: TextView = content.findViewById(R.id.btnView)
        val btnInstall: TextView = content.findViewById(R.id.btnInstall)

        btnFunctions.setOnClickListener {
            listener?.onFunctions(apkFile)
            dialog.dismiss()
        }
        btnView.setOnClickListener {
            listener?.onView(apkFile)
            dialog.dismiss()
        }
        btnInstall.setOnClickListener {
            listener?.onInstall(apkFile)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setRow(root: View, rowId: Int, label: String, value: String?) {
        val row = root.findViewById<View>(rowId) ?: return
        val l: TextView? = row.findViewById(R.id.label)
        val v: TextView? = row.findViewById(R.id.value)
        l?.text = label
        v?.text = if (!value.isNullOrEmpty()) value else "—"
    }
}
