package com.moimoi.app;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Links compartidos desde otras apps ("Compartir → MoiMoi" en YouTube, por ejemplo): se pasan
 * a la interfaz web, que los pone en "Agregar canción".
 *
 * Capacitor entrega a los plugins el intent con el que se abrió la app y los que llegan con la
 * app abierta (handleOnNewIntent). El evento queda guardado hasta que la página lo escuche.
 */
@CapacitorPlugin(name = "SharedLink")
public class SharedLinkPlugin extends Plugin {

    private String pending;

    @Override
    protected void handleOnNewIntent(Intent intent) {
        String text = sharedText(intent);
        if (text == null) {
            return;
        }
        pending = text;
        JSObject data = new JSObject();
        data.put("text", text);
        notifyListeners("shared", data, true);
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject result = new JSObject();
        result.put("text", pending);
        pending = null;
        call.resolve(result);
    }

    private static String sharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }
}
