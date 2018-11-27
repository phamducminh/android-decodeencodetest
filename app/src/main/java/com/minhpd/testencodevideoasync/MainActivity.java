package com.minhpd.testencodevideoasync;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Thread() {
            public void run() {
                ExtractDecodeEditEncodeMuxTest test = new ExtractDecodeEditEncodeMuxTest();
                test.setContext(MainActivity.this);
                try {
                    test.testExtractDecodeEditEncodeMuxAudioVideo();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }.start();
    }
}
