package com.bwl.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.btn_open_camera).setOnClickListener {
            if (checkPermission()) {
                gotoPreview()
            }
        }
    }

    private fun gotoPreview() {
        startActivity(Intent(this, PreviewActivity::class.java))
    }

    private fun checkPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0) {
            var isOk = true
            grantResults.forEach {
                if (it != PackageManager.PERMISSION_GRANTED) {
                    isOk = false
                }
            }
            if (isOk) {
                gotoPreview()
            } else {
                Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}