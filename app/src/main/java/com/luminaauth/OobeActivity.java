package com.luminaauth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.app.Activity;

public class OobeActivity extends Activity {
    private TextView tvStep;
    private Button btnRetry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oobe);

        tvStep = findViewById(R.id.tvStep);
        btnRetry = findViewById(R.id.btnRetry);

        startPermissions();
    }

    private void startPermissions() {
        tvStep.setText("正在申请权限...");
        btnRetry.setVisibility(View.GONE);
        PermissionHelper.start(this, new PermissionHelper.OnPermissionCallback() {
            @Override
            public void onAllGranted() {
                PrefUtils.setOobeCompleted(OobeActivity.this, true);
                startActivity(new Intent(OobeActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void onDenied(String permission) {
                tvStep.setText("权限 " + permission + " 被拒绝，应用可能无法正常工作。");
                btnRetry.setVisibility(View.VISIBLE);
                btnRetry.setOnClickListener(v -> startPermissions());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }
}
