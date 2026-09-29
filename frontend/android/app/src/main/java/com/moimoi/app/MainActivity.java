package com.moimoi.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugins propios de la app (los de npm se registran solos).
        registerPlugin(SharedLinkPlugin.class);
        registerPlugin(LocalPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
