package com.ari.recog;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Legacy entry: training is now the object gallery (free-form labels). */
public class TrainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.d("TrainActivity -> ModelActivity");
        startActivity(new Intent(this, ModelActivity.class));
        finish();
    }
}
