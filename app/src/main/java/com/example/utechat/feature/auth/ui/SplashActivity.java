package com.example.utechat.feature.auth.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.utechat.R;
import com.example.utechat.feature.main.ui.MainActivity;

/**
 * Màn hình khởi động (Splash).
 * Hiển thị logo UTEChat trong 1.5 giây, sau đó điều hướng đến Login
 * (khi tích hợp Firebase Auth ở G3: kiểm tra session và chuyển thẳng vào Main nếu đã đăng nhập).
 *
 * [MOCK] Hiện tại luôn chuyển sang LoginActivity — chưa kiểm tra session thật.
 */
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // TODO (G3): Thay bằng kiểm tra FirebaseAuth.getCurrentUser()
            // Nếu đã đăng nhập → MainActivity, chưa đăng nhập → LoginActivity
            navigateToLogin();
        }, SPLASH_DELAY_MS);
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish(); // Không cho phép Back về Splash
    }
}
