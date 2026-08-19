package com.pocketcontrol.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION =
            "com.pocketcontrol.app.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusView;
    private TextView logView;
    private PendingIntent permissionIntent;
    private boolean receiverRegistered = false;
    private String lastDiagnostic = "";

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }

            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);

            UsbDevice device = getUsbDeviceExtra(intent);
            UsbAccessory accessory = getUsbAccessoryExtra(intent);

            if (granted) {
                appendRuntime("Permissão USB concedida pelo Android.");
                if (device != null) {
                    testOpenDevice(device);
                } else if (accessory != null) {
                    testOpenAccessory(accessory);
                }
            } else {
                appendRuntime("Permissão USB negada.");
            }

            refreshUsb();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        createPermissionIntent();
        registerPermissionReceiver();
        buildUi();
        refreshUsb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            refreshUsb();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        appendRuntime("Novo evento USB recebido pelo aplicativo.");
        refreshUsb();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private void createPermissionIntent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // O UsbManager adiciona o dispositivo/acessório e o resultado da
            // permissão ao PendingIntent; por isso ele precisa ser mutável.
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    private void registerPermissionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void buildUi() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Pocket Control 0.1");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Diagnóstico USB para DJI Osmo Pocket 1");
        subtitle.setTextSize(15f);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(18f);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, dp(8));

        Button refreshButton = new Button(this);
        refreshButton.setText("Atualizar USB");
        refreshButton.setOnClickListener(v -> refreshUsb());
        buttons.addView(refreshButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button permissionButton = new Button(this);
        permissionButton.setText("Pedir acesso");
        permissionButton.setOnClickListener(v -> requestFirstUsbPermission());
        buttons.addView(permissionButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);

        Button copyButton = new Button(this);
        copyButton.setText("Copiar diagnóstico");
        copyButton.setOnClickListener(v -> copyDiagnostic());
        root.addView(copyButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(13f);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(4), dp(10), dp(4), dp(16));
        scroll.addView(logView);

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refreshUsb() {
        try {
            if (usbManager == null) {
                statusView.setText("❌ Serviço USB indisponível neste aparelho.");
                logView.setText("O Android não forneceu UsbManager.");
                return;
            }

            StringBuilder out = new StringBuilder();
            out.append("Pocket Control 0.1\n");
            out.append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            out.append("Aparelho: ").append(Build.MANUFACTURER)
                    .append(" ").append(Build.MODEL).append("\n");
            out.append("ABI principal: ").append(Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : "desconhecida").append("\n\n");

            boolean found = false;
            boolean djiLikely = false;

            UsbAccessory[] accessories = null;
            try {
                accessories = usbManager.getAccessoryList();
            } catch (Throwable t) {
                out.append("Falha ao enumerar USB Accessory: ")
                        .append(t.getClass().getSimpleName()).append("\n");
            }

            if (accessories != null && accessories.length > 0) {
                found = true;
                out.append("=== USB ACCESSORY ===\n");
                for (int i = 0; i < accessories.length; i++) {
                    UsbAccessory a = accessories[i];
                    String manufacturer = safe(a.getManufacturer());
                    String model = safe(a.getModel());
                    if (containsDji(manufacturer) || "HG210".equalsIgnoreCase(model)) {
                        djiLikely = true;
                    }
                    out.append("Acessório #").append(i + 1).append("\n")
                            .append("Fabricante: ").append(manufacturer).append("\n")
                            .append("Modelo: ").append(model).append("\n")
                            .append("Descrição: ").append(safe(a.getDescription())).append("\n")
                            .append("Versão: ").append(safe(a.getVersion())).append("\n")
                            .append("Permissão: ").append(usbManager.hasPermission(a) ? "SIM" : "NÃO")
                            .append("\n\n");
                }
            }

            HashMap<String, UsbDevice> devices = null;
            try {
                devices = usbManager.getDeviceList();
            } catch (Throwable t) {
                out.append("Falha ao enumerar USB Host: ")
                        .append(t.getClass().getSimpleName()).append("\n");
            }

            if (devices != null && !devices.isEmpty()) {
                found = true;
                out.append("=== USB DEVICES ===\n");
                int index = 0;
                for (UsbDevice d : devices.values()) {
                    index++;
                    String manufacturer = safe(callManufacturer(d));
                    String product = safe(callProduct(d));
                    if (containsDji(manufacturer) || containsDji(product)) {
                        djiLikely = true;
                    }

                    out.append("Dispositivo #").append(index).append("\n")
                            .append("Nome: ").append(safe(d.getDeviceName())).append("\n")
                            .append("Fabricante: ").append(manufacturer).append("\n")
                            .append("Produto: ").append(product).append("\n")
                            .append("VID: ").append(d.getVendorId())
                            .append(" (0x").append(hex4(d.getVendorId())).append(")\n")
                            .append("PID: ").append(d.getProductId())
                            .append(" (0x").append(hex4(d.getProductId())).append(")\n")
                            .append("Classe: ").append(d.getDeviceClass()).append("\n")
                            .append("Subclasse: ").append(d.getDeviceSubclass()).append("\n")
                            .append("Protocolo: ").append(d.getDeviceProtocol()).append("\n")
                            .append("Permissão: ").append(usbManager.hasPermission(d) ? "SIM" : "NÃO")
                            .append("\n")
                            .append(describeDeviceStructure(d))
                            .append("\n");
                }
            }

            Intent launchIntent = getIntent();
            if (launchIntent != null && launchIntent.getAction() != null) {
                out.append("Intent atual: ").append(launchIntent.getAction()).append("\n\n");
            }

            if (!found) {
                statusView.setText("⚪ Nenhum dispositivo USB detectado");
                out.append("Nenhum USB Device/Accessory foi encontrado.\n")
                        .append("Conecte a Osmo Pocket 1, ligue-a e toque em Atualizar USB.\n");
            } else if (djiLikely) {
                statusView.setText("✅ Possível DJI / Osmo detectada");
            } else {
                statusView.setText("🟡 USB detectado — precisamos identificar");
            }

            lastDiagnostic = out.toString();
            logView.setText(lastDiagnostic);
        } catch (Throwable t) {
            // Esta versão de diagnóstico não deve fechar mesmo se um fabricante
            // retornar dados USB inesperados.
            statusView.setText("⚠️ Erro capturado — o app continuou aberto");
            lastDiagnostic = "Erro em refreshUsb():\n"
                    + t.getClass().getName() + ": " + safe(t.getMessage());
            logView.setText(lastDiagnostic);
        }
    }

    private String describeDeviceStructure(UsbDevice d) {
        StringBuilder out = new StringBuilder();
        try {
            out.append("Configurações: ").append(d.getConfigurationCount()).append("\n");
            for (int c = 0; c < d.getConfigurationCount(); c++) {
                UsbConfiguration cfg = d.getConfiguration(c);
                out.append("  Config #").append(c)
                        .append(" interfaces=").append(cfg.getInterfaceCount())
                        .append("\n");
                for (int i = 0; i < cfg.getInterfaceCount(); i++) {
                    UsbInterface intf = cfg.getInterface(i);
                    out.append("    Interface #").append(i)
                            .append(" id=").append(intf.getId())
                            .append(" class=").append(intf.getInterfaceClass())
                            .append(" subclass=").append(intf.getInterfaceSubclass())
                            .append(" protocol=").append(intf.getInterfaceProtocol())
                            .append(" endpoints=").append(intf.getEndpointCount())
                            .append("\n");
                    for (int e = 0; e < intf.getEndpointCount(); e++) {
                        UsbEndpoint ep = intf.getEndpoint(e);
                        out.append("      EP #").append(e)
                                .append(" addr=0x").append(Integer.toHexString(ep.getAddress()))
                                .append(" dir=").append(ep.getDirection())
                                .append(" type=").append(ep.getType())
                                .append(" maxPacket=").append(ep.getMaxPacketSize())
                                .append("\n");
                    }
                }
            }
        } catch (Throwable t) {
            out.append("  Falha ao ler interfaces/endpoints: ")
                    .append(t.getClass().getSimpleName()).append("\n");
        }
        return out.toString();
    }

    private void requestFirstUsbPermission() {
        try {
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (accessories != null && accessories.length > 0) {
                UsbAccessory a = accessories[0];
                if (usbManager.hasPermission(a)) {
                    Toast.makeText(this, "A permissão para o acessório já foi concedida.",
                            Toast.LENGTH_SHORT).show();
                    testOpenAccessory(a);
                    refreshUsb();
                } else {
                    usbManager.requestPermission(a, permissionIntent);
                }
                return;
            }

            HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
            if (devices != null && !devices.isEmpty()) {
                UsbDevice d = devices.values().iterator().next();
                if (usbManager.hasPermission(d)) {
                    Toast.makeText(this, "A permissão para o dispositivo já foi concedida.",
                            Toast.LENGTH_SHORT).show();
                    testOpenDevice(d);
                    refreshUsb();
                } else {
                    usbManager.requestPermission(d, permissionIntent);
                }
                return;
            }

            Toast.makeText(this, "Nenhum USB conectado.", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            appendRuntime("Falha ao pedir acesso: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
        }
    }

    private void testOpenDevice(UsbDevice d) {
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(d);
            if (connection != null) {
                appendRuntime("Canal USB Device abriu com sucesso. fd="
                        + connection.getFileDescriptor());
            } else {
                appendRuntime("Android retornou null ao abrir USB Device.");
            }
        } catch (Throwable t) {
            appendRuntime("Falha ao abrir USB Device: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void testOpenAccessory(UsbAccessory a) {
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = usbManager.openAccessory(a);
            if (descriptor != null) {
                appendRuntime("Canal USB Accessory abriu com sucesso. fd="
                        + descriptor.getFd());
            } else {
                appendRuntime("Android retornou null ao abrir USB Accessory.");
            }
        } catch (Throwable t) {
            appendRuntime("Falha ao abrir USB Accessory: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void copyDiagnostic() {
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Pocket Control diagnóstico", lastDiagnostic));
            Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendRuntime(String message) {
        if (logView == null) {
            return;
        }
        String existing = logView.getText() == null ? "" : logView.getText().toString();
        String updated = existing + "\n[EVENTO] " + message + "\n";
        logView.setText(updated);
        lastDiagnostic = updated;
    }

    private boolean containsDji(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("dji");
    }

    private String callManufacturer(UsbDevice d) {
        try {
            return d.getManufacturerName();
        } catch (Throwable t) {
            return "indisponível";
        }
    }

    private String callProduct(UsbDevice d) {
        try {
            return d.getProductName();
        } catch (Throwable t) {
            return "indisponível";
        }
    }

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @SuppressWarnings("deprecation")
    private UsbAccessory getUsbAccessoryExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
        }
        return (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }

    private String safe(String s) {
        return s == null ? "-" : s;
    }

    private String hex4(int value) {
        return String.format(Locale.US, "%04X", value & 0xFFFF);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
