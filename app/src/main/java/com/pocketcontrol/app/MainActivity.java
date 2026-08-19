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
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

    private final StringBuilder eventLog = new StringBuilder();
    private String baseDiagnostic = "";
    private String lastDiagnostic = "";

    private ParcelFileDescriptor accessoryDescriptor;
    private FileInputStream accessoryInput;
    private FileOutputStream accessoryOutput;
    private volatile boolean listening = false;
    private Thread listenThread;
    private final ByteArrayOutputStream captureBuffer = new ByteArrayOutputStream();
    private int nextTxSequence = 1;

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
                appendEvent("Permissão USB concedida pelo Android.");
                if (accessory != null) {
                    openAccessoryChannel(accessory);
                } else if (device != null) {
                    testOpenDevice(device);
                }
            } else {
                appendEvent("Permissão USB negada.");
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
        appendEvent("Novo evento USB recebido: " + safe(intent.getAction()));
        refreshUsb();
    }

    @Override
    protected void onDestroy() {
        stopListening();
        closeAccessoryChannel();

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
        title.setText("Pocket Control 0.6");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Leitura + primeiro teste TX seguro da Osmo Pocket 1");
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

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, dp(4));

        Button refreshButton = new Button(this);
        refreshButton.setText("Atualizar USB");
        refreshButton.setOnClickListener(v -> refreshUsb());
        row1.addView(refreshButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button openButton = new Button(this);
        openButton.setText("Abrir canal");
        openButton.setOnClickListener(v -> openFirstAccessory());
        row1.addView(openButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(4), 0, dp(8));

        Button listenButton = new Button(this);
        listenButton.setText("Escutar 10 s");
        listenButton.setOnClickListener(v -> startPassiveListen());
        row2.addView(listenButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button closeButton = new Button(this);
        closeButton.setText("Fechar canal");
        closeButton.setOnClickListener(v -> {
            stopListening();
            closeAccessoryChannel();
        });
        row2.addView(closeButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(row2);

        Button txTestButton = new Button(this);
        txTestButton.setText("TESTAR TX — LER MODO DA CÂMERA");
        txTestButton.setOnClickListener(v -> sendReadOnlyCameraModeQuery());
        root.addView(txTestButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
                baseDiagnostic = "O Android não forneceu UsbManager.";
                renderLog();
                return;
            }

            StringBuilder out = new StringBuilder();
            out.append("Pocket Control 0.6\n");
            out.append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            out.append("Aparelho: ").append(Build.MANUFACTURER)
                    .append(" ").append(Build.MODEL).append("\n");
            out.append("ABIs: ");
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(Build.SUPPORTED_ABIS[i]);
                }
            } else {
                out.append("desconhecidas");
            }
            out.append("\n\n");

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
                            .append("\n")
                            .append("Canal aberto: ").append(accessoryDescriptor != null ? "SIM" : "NÃO")
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
            } else if (djiLikely && accessoryDescriptor != null) {
                statusView.setText("🟢 Osmo detectada — canal aberto");
            } else if (djiLikely) {
                statusView.setText("✅ Osmo Pocket detectada");
            } else {
                statusView.setText("🟡 USB detectado — precisamos identificar");
            }

            baseDiagnostic = out.toString();
            renderLog();
        } catch (Throwable t) {
            statusView.setText("⚠️ Erro capturado — o app continuou aberto");
            baseDiagnostic = "Erro em refreshUsb():\n"
                    + t.getClass().getName() + ": " + safe(t.getMessage());
            renderLog();
        }
    }

    private void openFirstAccessory() {
        try {
            UsbAccessory[] accessories = usbManager.getAccessoryList();
            if (accessories == null || accessories.length == 0) {
                appendEvent("Nenhum USB Accessory conectado.");
                Toast.makeText(this, "Nenhuma Osmo detectada.", Toast.LENGTH_SHORT).show();
                return;
            }

            UsbAccessory accessory = accessories[0];
            if (!usbManager.hasPermission(accessory)) {
                appendEvent("Solicitando permissão para o acessório...");
                usbManager.requestPermission(accessory, permissionIntent);
                return;
            }

            openAccessoryChannel(accessory);
        } catch (Throwable t) {
            appendEvent("Falha ao abrir acessório: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
        }
    }

    private synchronized void openAccessoryChannel(UsbAccessory accessory) {
        if (accessoryDescriptor != null) {
            appendEvent("O canal já está aberto.");
            return;
        }

        try {
            accessoryDescriptor = usbManager.openAccessory(accessory);
            if (accessoryDescriptor == null) {
                appendEvent("Android retornou null ao abrir USB Accessory.");
                statusView.setText("❌ Não foi possível abrir o canal");
                return;
            }

            accessoryInput = new FileInputStream(accessoryDescriptor.getFileDescriptor());
            accessoryOutput = new FileOutputStream(accessoryDescriptor.getFileDescriptor());

            appendEvent("Canal USB Accessory aberto. fd=" + accessoryDescriptor.getFd());
            appendEvent("0.6: nenhum comando é enviado automaticamente. O botão TX envia apenas uma consulta de leitura.");
            statusView.setText("🟢 Osmo detectada — canal aberto");
            refreshUsb();
        } catch (Throwable t) {
            appendEvent("Erro ao abrir canal: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
            closeAccessoryChannel();
        }
    }


    /**
     * Primeiro teste de transmissão do projeto.
     *
     * Envia somente CAMERA / Camera Work Mode Get (cmd set 0x02, cmd 0x11).
     * Este comando é de consulta e não altera configuração, gravação ou gimbal.
     */
    private synchronized void sendReadOnlyCameraModeQuery() {
        if (listening) {
            Toast.makeText(this,
                    "Espere a escuta de 10 s terminar antes do teste TX.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (accessoryDescriptor == null || accessoryOutput == null || accessoryInput == null) {
            openFirstAccessory();
            if (accessoryDescriptor == null || accessoryOutput == null || accessoryInput == null) {
                appendEvent("TX cancelado: não foi possível abrir o canal USB Accessory.");
                return;
            }
        }

        final int seq = nextTxSequence & 0xFFFF;
        nextTxSequence = (nextTxSequence + 1) & 0xFFFF;
        if (nextTxSequence == 0) nextTxSequence = 1;

        try {
            // Sender: App (2), Receiver: Camera (1)
            // Request, ACK after execution, no encryption.
            byte[] duml = buildDumlPacket(
                    0x02, 0x01, seq,
                    0, 2, 0,
                    0x02, 0x11,
                    new byte[0]);

            byte[] transport = wrapPocketTransport(duml);

            accessoryOutput.write(transport);
            accessoryOutput.flush();

            appendEvent("TX seguro enviado: Camera Work Mode Get (0x02/0x11), seq="
                    + seq + ", " + transport.length + " bytes no transporte.");
            appendEvent("DUML TX: " + toHex(duml));
            statusView.setText("📤 Consulta enviada — aguardando resposta...");

            listenForReadOnlyResponse(seq, 0x02, 0x11, 2200L);
        } catch (Throwable t) {
            appendEvent("Falha no TX seguro: " + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));
            statusView.setText("❌ Falha ao transmitir consulta");
        }
    }

    private void listenForReadOnlyResponse(
            final int expectedSeq,
            final int expectedCmdSet,
            final int expectedCmdId,
            final long timeoutMs) {

        if (listening) {
            appendEvent("Leitor ocupado; resposta TX não será monitorada agora.");
            return;
        }

        listening = true;

        listenThread = new Thread(() -> {
            ByteArrayOutputStream rx = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[4096];

            try {
                StructPollfd pollfd = new StructPollfd();
                pollfd.fd = accessoryDescriptor.getFileDescriptor();
                pollfd.events = (short) OsConstants.POLLIN;
                StructPollfd[] pollfds = new StructPollfd[]{pollfd};

                while (listening && System.currentTimeMillis() < deadline) {
                    pollfd.revents = 0;
                    int ready = Os.poll(pollfds, 120);

                    if (!listening) break;

                    if (ready > 0 && (pollfd.revents & OsConstants.POLLIN) != 0) {
                        int count = accessoryInput.read(buffer);
                        if (count < 0) break;
                        if (count > 0) {
                            rx.write(buffer, 0, count);
                            if (rx.size() >= 32768) break;
                        }
                    }
                }
            } catch (Throwable t) {
                appendEventFromWorker("Erro ao aguardar resposta TX: "
                        + t.getClass().getSimpleName() + " - " + safe(t.getMessage()));
            } finally {
                listening = false;

                final byte[] received = rx.toByteArray();
                final String result = findCommandResponse(
                        received, expectedSeq, expectedCmdSet, expectedCmdId);

                runOnUiThread(() -> {
                    appendEvent("RESULTADO DO TESTE TX\n" + result);
                    statusView.setText(accessoryDescriptor != null
                            ? "🟢 Canal aberto — teste TX concluído"
                            : "✅ Teste TX concluído");
                });
            }
        }, "PocketReadOnlyTxResponse");

        listenThread.start();
    }

    private String findCommandResponse(
            byte[] data, int expectedSeq, int expectedCmdSet, int expectedCmdId) {

        if (data == null || data.length == 0) {
            return "Nenhum byte recebido após a consulta.";
        }

        int outerCount = 0;
        int frameCount = 0;
        int responseCount = 0;
        StringBuilder matches = new StringBuilder();

        int pos = 0;
        while (pos + 8 <= data.length) {
            if (!looksLikePocketTransportHeader(data, pos)) {
                pos++;
                continue;
            }

            long payloadLengthLong = readLe32(data, pos + 4);
            if (payloadLengthLong < 0 || payloadLengthLong > Integer.MAX_VALUE) {
                pos++;
                continue;
            }

            int transportPayloadLength = (int) payloadLengthLong;
            int payloadStart = pos + 8;
            int payloadEnd = Math.min(data.length, payloadStart + transportPayloadLength);
            outerCount++;

            int p = payloadStart;
            while (p + 13 <= payloadEnd) {
                if ((data[p] & 0xFF) != 0x55) {
                    p++;
                    continue;
                }

                int frameLength =
                        (data[p + 1] & 0xFF) | ((data[p + 2] & 0x03) << 8);

                if (frameLength < 13 || p + frameLength > payloadEnd) {
                    p++;
                    continue;
                }

                frameCount++;

                int sender = data[p + 4] & 0x1F;
                int receiver = data[p + 5] & 0x1F;
                int seq = (data[p + 6] & 0xFF) | ((data[p + 7] & 0xFF) << 8);
                int cmdType = data[p + 8] & 0xFF;
                int packetType = (cmdType >> 7) & 0x01;
                int ackType = (cmdType >> 5) & 0x03;
                int encryptType = cmdType & 0x07;
                int cmdSet = data[p + 9] & 0xFF;
                int cmdId = data[p + 10] & 0xFF;
                int innerPayloadLength = frameLength - 13;
                int innerPayloadStart = p + 11;

                if (packetType == 1
                        && cmdSet == expectedCmdSet
                        && cmdId == expectedCmdId
                        && seq == expectedSeq) {

                    responseCount++;

                    byte[] payload = new byte[Math.max(0, innerPayloadLength)];
                    if (innerPayloadLength > 0) {
                        System.arraycopy(
                                data, innerPayloadStart,
                                payload, 0, innerPayloadLength);
                    }

                    matches.append("Resposta #").append(responseCount)
                            .append(": ")
                            .append(sourceName(sender)).append(" → ")
                            .append(sourceName(receiver))
                            .append(", seq=").append(seq)
                            .append(", ACK=").append(ackType)
                            .append(", ENC=").append(encryptType)
                            .append(", payload=").append(innerPayloadLength)
                            .append(" byte(s)\n");

                    matches.append("Payload HEX: ")
                            .append(payload.length == 0 ? "(vazio)" : toHex(payload))
                            .append("\n");

                    if (payload.length == 1) {
                        int value = payload[0] & 0xFF;
                        matches.append("Possível modo retornado: ")
                                .append(cameraModeName(value))
                                .append(" (").append(value).append(")\n");
                    } else if (payload.length >= 2) {
                        int first = payload[0] & 0xFF;
                        int second = payload[1] & 0xFF;
                        matches.append("Primeiros valores: ")
                                .append(first).append(", ").append(second)
                                .append(". Se o primeiro for status=0, o segundo pode ser o modo: ")
                                .append(cameraModeName(second))
                                .append(" (").append(second).append(")\n");
                    }

                    matches.append("\n");
                }

                p += frameLength;
            }

            if (payloadStart + transportPayloadLength <= data.length) {
                pos = payloadStart + transportPayloadLength;
            } else {
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Recebidos ").append(data.length).append(" bytes em ~2,2 s.\n");
        out.append("Blocos DJI: ").append(outerCount)
                .append(" | Quadros DUML: ").append(frameCount).append("\n");

        if (responseCount == 0) {
            out.append("Nenhuma resposta 0x")
                    .append(hex2(expectedCmdSet)).append("/0x")
                    .append(hex2(expectedCmdId))
                    .append(" com seq=").append(expectedSeq)
                    .append(" foi encontrada.\n")
                    .append("Isso NÃO significa que o canal de saída está quebrado; ")
                    .append("pode indicar que a Pocket usa outro envelope/ACK para comandos do app.");
        } else {
            out.append("Resposta correspondente encontrada: ")
                    .append(responseCount).append("\n\n")
                    .append(matches);
        }

        return out.toString();
    }

    private byte[] buildDumlPacket(
            int senderInfo,
            int receiverInfo,
            int seq,
            int packetType,
            int ackType,
            int encryptType,
            int cmdSet,
            int cmdId,
            byte[] payload) {

        if (payload == null) payload = new byte[0];

        int wholeLength = 11 + payload.length + 2;
        if (wholeLength > 1023) {
            throw new IllegalArgumentException("Pacote DUML grande demais");
        }

        byte[] out = new byte[wholeLength];

        out[0] = 0x55;

        int verLengthTag = ((1 & 0x3F) << 10) | (wholeLength & 0x03FF);
        out[1] = (byte) (verLengthTag & 0xFF);
        out[2] = (byte) ((verLengthTag >> 8) & 0xFF);

        out[3] = (byte) crc8Dji(0x77, out, 0, 3);

        out[4] = (byte) (senderInfo & 0xFF);
        out[5] = (byte) (receiverInfo & 0xFF);
        out[6] = (byte) (seq & 0xFF);
        out[7] = (byte) ((seq >> 8) & 0xFF);

        int cmdTypeData =
                ((packetType & 0x01) << 7)
                | ((ackType & 0x03) << 5)
                | (encryptType & 0x07);

        out[8] = (byte) cmdTypeData;
        out[9] = (byte) (cmdSet & 0xFF);
        out[10] = (byte) (cmdId & 0xFF);

        if (payload.length > 0) {
            System.arraycopy(payload, 0, out, 11, payload.length);
        }

        int crc16 = crc16Dji(out, 0, wholeLength - 2);
        out[wholeLength - 2] = (byte) (crc16 & 0xFF);
        out[wholeLength - 1] = (byte) ((crc16 >> 8) & 0xFF);

        return out;
    }

    private byte[] wrapPocketTransport(byte[] duml) {
        byte[] out = new byte[8 + duml.length];

        out[0] = 0x55;
        out[1] = (byte) 0xCC;
        out[2] = 0x49;
        out[3] = 0x57;

        int length = duml.length;
        out[4] = (byte) (length & 0xFF);
        out[5] = (byte) ((length >> 8) & 0xFF);
        out[6] = (byte) ((length >> 16) & 0xFF);
        out[7] = (byte) ((length >> 24) & 0xFF);

        System.arraycopy(duml, 0, out, 8, duml.length);
        return out;
    }

    /**
     * DJI DUML header CRC-8.
     * Equivalente à tabela usada pelo dji-firmware-tools:
     * seed 0x77, polinômio refletido 0x8C.
     */
    private int crc8Dji(int seed, byte[] data, int offset, int length) {
        int crc = seed & 0xFF;

        for (int i = 0; i < length; i++) {
            crc ^= data[offset + i] & 0xFF;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x01) != 0) {
                    crc = (crc >> 1) ^ 0x8C;
                } else {
                    crc >>= 1;
                }
                crc &= 0xFF;
            }
        }

        return crc & 0xFF;
    }

    /**
     * DJI DUML packet CRC-16.
     * Seed 0x3692; implementação bit a bit equivalente à tabela
     * usada pelo dji-firmware-tools.
     */
    private int crc16Dji(byte[] data, int offset, int length) {
        int crc = 0x3692;

        for (int i = 0; i < length; i++) {
            crc ^= data[offset + i] & 0xFF;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0x8408;
                } else {
                    crc >>= 1;
                }
                crc &= 0xFFFF;
            }
        }

        return crc & 0xFFFF;
    }

    private synchronized void closeAccessoryChannel() {
        listening = false;

        if (accessoryInput != null) {
            try {
                accessoryInput.close();
            } catch (Exception ignored) {
            }
            accessoryInput = null;
        }

        if (accessoryOutput != null) {
            try {
                accessoryOutput.close();
            } catch (Exception ignored) {
            }
            accessoryOutput = null;
        }

        if (accessoryDescriptor != null) {
            try {
                accessoryDescriptor.close();
            } catch (Exception ignored) {
            }
            accessoryDescriptor = null;
            appendEvent("Canal USB fechado.");
        }

        refreshUsb();
    }

    private void startPassiveListen() {
        if (listening) {
            Toast.makeText(this, "A escuta já está em andamento.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (accessoryDescriptor == null || accessoryInput == null) {
            openFirstAccessory();
            if (accessoryDescriptor == null || accessoryInput == null) {
                appendEvent("Não foi possível iniciar a escuta: canal não abriu.");
                return;
            }
        }

        listening = true;
        captureBuffer.reset();
        appendEvent("Escuta passiva iniciada por 10 segundos. Não enviaremos comandos.");
        statusView.setText("👂 Escutando a Osmo por 10 s...");

        listenThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 10_000L;
            int total = 0;
            int chunks = 0;
            byte[] buffer = new byte[4096];

            try {
                StructPollfd pollfd = new StructPollfd();
                pollfd.fd = accessoryDescriptor.getFileDescriptor();
                pollfd.events = (short) OsConstants.POLLIN;
                StructPollfd[] pollfds = new StructPollfd[]{pollfd};

                while (listening && System.currentTimeMillis() < deadline) {
                    pollfd.revents = 0;
                    int ready = Os.poll(pollfds, 250);
                    if (!listening) {
                        break;
                    }

                    if (ready > 0 && (pollfd.revents & OsConstants.POLLIN) != 0) {
                        int count = accessoryInput.read(buffer);
                        if (count < 0) {
                            appendEventFromWorker("A câmera encerrou o fluxo de leitura.");
                            break;
                        }
                        if (count > 0) {
                            total += count;
                            chunks++;
                            byte[] packet = new byte[count];
                            System.arraycopy(buffer, 0, packet, 0, count);
                            captureBuffer.write(packet, 0, packet.length);
                            appendEventFromWorker("RX #" + chunks + " — " + count + " bytes");

                            if (total >= 65536) {
                                appendEventFromWorker("Limite de captura atingido (64 KiB).");
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                if (listening) {
                    appendEventFromWorker("Erro durante leitura: "
                            + t.getClass().getSimpleName() + " - " + safe(t.getMessage()));
                }
            } finally {
                listening = false;
                final int bytes = total;
                final int packetCount = chunks;
                final byte[] captured = captureBuffer.toByteArray();
                final String automaticAnalysis = analyzeCapture(captured);
                runOnUiThread(() -> {
                    appendEvent("Escuta finalizada: " + bytes + " bytes em "
                            + packetCount + " bloco(s).");
                    appendEvent("ANÁLISE AUTOMÁTICA DA CAPTURA\n" + automaticAnalysis);
                    if (bytes == 0) {
                        appendEvent("Zero bytes não significa falha: a Pocket pode esperar "
                                + "um comando de protocolo antes de responder.");
                    }
                    statusView.setText(accessoryDescriptor != null
                            ? "🟢 Canal aberto — escuta concluída"
                            : "✅ Osmo Pocket detectada");
                });
            }
        }, "PocketPassiveReader");

        listenThread.start();
    }

    private void stopListening() {
        listening = false;
        Thread t = listenThread;
        listenThread = null;
        if (t != null) {
            t.interrupt();
        }
    }


    private String analyzeCapture(byte[] data) {
        if (data == null || data.length == 0) {
            return "Nenhum byte disponível para análise.";
        }

        HashMap<String, Integer> commandCounts = new HashMap<>();

        int outerCount = 0;
        int frameCount = 0;
        int pos = 0;

        // CAMERA 0x80 - Camera State Info
        boolean haveCameraState = false;
        int cameraFlags = 0;
        int cameraMode = -1;
        int cameraRecordState = -1;
        int cameraPhotoState = -1;
        boolean cameraConnected = false;
        boolean cameraUsbState = false;
        boolean cameraTimeSynced = false;
        boolean cameraSdInserted = false;
        int cameraSdState = -1;
        long cameraSdTotal = -1;
        long cameraSdFree = -1;
        long cameraRemainingShots = -1;
        long cameraRemainingTime = -1;
        int cameraRecordTime = -1;
        boolean cameraHistogramEnabled = false;
        int cameraType = -1;
        int cameraStateVersion = -1;

        // CAMERA 0x81 - Camera Shot Params
        boolean haveShotParams = false;
        int apertureRaw = -1;
        int shutterRaw = -1;
        int shutterDecimal = -1;
        int isoCode = -1;
        int exposureCompRaw = -1;
        int imageRatioCode = -1;
        int videoFormatCode = -1;
        int videoFpsCode = -1;
        int videoFovCode = -1;
        int exposureModeCode = -1;
        int whiteBalanceCode = -1;
        int colorTempCode = -1;
        int antiFlickerCode = -1;

        // GIMBAL 0x05 - Gimbal Params / Push Position
        boolean haveGimbal = false;
        double gimbalPitch = 0.0;
        double gimbalRoll = 0.0;
        double gimbalYaw = 0.0;
        int gimbalMode = -1;
        boolean gimbalPitchLimit = false;
        boolean gimbalRollLimit = false;
        boolean gimbalYawLimit = false;
        boolean gimbalCalibrating = false;
        boolean gimbalStuck = false;
        int gimbalVersion = -1;

        while (pos + 8 <= data.length) {
            if (!looksLikePocketTransportHeader(data, pos)) {
                pos++;
                continue;
            }

            long payloadLengthLong = readLe32(data, pos + 4);
            if (payloadLengthLong < 0 || payloadLengthLong > Integer.MAX_VALUE) {
                pos++;
                continue;
            }

            int payloadLength = (int) payloadLengthLong;
            int payloadStart = pos + 8;
            int payloadEnd = payloadStart + payloadLength;

            if (payloadEnd > data.length) {
                payloadEnd = data.length;
            }

            outerCount++;

            int p = payloadStart;
            while (p + 13 <= payloadEnd) {
                if ((data[p] & 0xFF) != 0x55) {
                    p++;
                    continue;
                }

                int b1 = data[p + 1] & 0xFF;
                int b2 = data[p + 2] & 0xFF;
                int frameLength = b1 | ((b2 & 0x03) << 8);

                if (frameLength < 13 || p + frameLength > payloadEnd) {
                    p++;
                    continue;
                }

                int sender = data[p + 4] & 0xFF;
                int receiver = data[p + 5] & 0xFF;
                int cmdSet = data[p + 9] & 0xFF;
                int cmdId = data[p + 10] & 0xFF;
                int innerPayloadStart = p + 11;
                int innerPayloadLength = frameLength - 13;

                frameCount++;

                String key = sourceName(sender)
                        + " → " + sourceName(receiver)
                        + " | " + commandName(cmdSet, cmdId)
                        + " [set 0x" + hex2(cmdSet)
                        + ", id 0x" + hex2(cmdId) + "]";

                Integer oldCount = commandCounts.get(key);
                commandCounts.put(key, oldCount == null ? 1 : oldCount + 1);

                // Camera State Info (0x80)
                if (cmdSet == 0x02 && cmdId == 0x80 && innerPayloadLength >= 5) {
                    cameraFlags = readLe32Int(data, innerPayloadStart);
                    cameraMode = data[innerPayloadStart + 4] & 0xFF;

                    cameraConnected = (cameraFlags & 0x0001) != 0;
                    cameraUsbState = (cameraFlags & 0x0002) != 0;
                    cameraTimeSynced = (cameraFlags & 0x0004) != 0;
                    cameraPhotoState = (cameraFlags >> 3) & 0x07;
                    cameraRecordState = (cameraFlags >> 6) & 0x03;
                    cameraSdInserted = (cameraFlags & 0x0200) != 0;
                    cameraSdState = (cameraFlags >> 10) & 0x0F;

                    if (innerPayloadLength >= 37) {
                        cameraSdTotal = readLe32Unsigned(data, innerPayloadStart + 5);
                        cameraSdFree = readLe32Unsigned(data, innerPayloadStart + 9);
                        cameraRemainingShots = readLe32Unsigned(data, innerPayloadStart + 13);
                        cameraRemainingTime = readLe32Unsigned(data, innerPayloadStart + 17);
                        cameraRecordTime = readLe16Unsigned(data, innerPayloadStart + 29);
                        cameraHistogramEnabled =
                                (data[innerPayloadStart + 32] & 0x01) != 0;
                        cameraType = data[innerPayloadStart + 33] & 0xFF;
                        cameraStateVersion = data[innerPayloadStart + 36] & 0xFF;
                    }

                    haveCameraState = true;
                }

                // Camera Shot Params (0x81)
                if (cmdSet == 0x02 && cmdId == 0x81 && innerPayloadLength >= 25) {
                    apertureRaw = readLe16Unsigned(data, innerPayloadStart);
                    shutterRaw = readLe16Unsigned(data, innerPayloadStart + 2);
                    shutterDecimal = data[innerPayloadStart + 4] & 0xFF;
                    isoCode = data[innerPayloadStart + 5] & 0xFF;
                    exposureCompRaw = data[innerPayloadStart + 6] & 0xFF;
                    imageRatioCode = data[innerPayloadStart + 10] & 0xFF;
                    videoFormatCode = data[innerPayloadStart + 13] & 0xFF;
                    videoFpsCode = data[innerPayloadStart + 14] & 0xFF;
                    videoFovCode = data[innerPayloadStart + 15] & 0xFF;
                    exposureModeCode = data[innerPayloadStart + 20] & 0xFF;
                    whiteBalanceCode = data[innerPayloadStart + 23] & 0xFF;
                    colorTempCode = data[innerPayloadStart + 24] & 0xFF;

                    if (innerPayloadLength >= 34) {
                        antiFlickerCode = data[innerPayloadStart + 33] & 0xFF;
                    }

                    haveShotParams = true;
                }

                // Gimbal Params / Push Position (0x05)
                if (cmdSet == 0x04 && cmdId == 0x05 && innerPayloadLength >= 7) {
                    int pitchRaw = readLe16Signed(data, innerPayloadStart);
                    int rollRaw = readLe16Signed(data, innerPayloadStart + 2);
                    int yawRaw = readLe16Signed(data, innerPayloadStart + 4);
                    int modeByte = data[innerPayloadStart + 6] & 0xFF;

                    gimbalPitch = pitchRaw / 10.0;
                    gimbalRoll = rollRaw / 10.0;
                    gimbalYaw = yawRaw / 10.0;
                    gimbalMode = (modeByte >> 6) & 0x03;

                    if (innerPayloadLength >= 12) {
                        int flags10 = data[innerPayloadStart + 10] & 0xFF;
                        int flags11 = data[innerPayloadStart + 11] & 0xFF;

                        gimbalPitchLimit = (flags10 & 0x01) != 0;
                        gimbalRollLimit = (flags10 & 0x02) != 0;
                        gimbalYawLimit = (flags10 & 0x04) != 0;
                        gimbalCalibrating = (flags10 & 0x08) != 0;
                        gimbalStuck = (flags10 & 0x40) != 0;
                        gimbalVersion = flags11 & 0x0F;
                    }

                    haveGimbal = true;
                }

                p += frameLength;
            }

            if (payloadStart + payloadLength <= data.length) {
                pos = payloadStart + payloadLength;
            } else {
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Total capturado: ").append(data.length).append(" bytes\n");
        out.append("Blocos USB DJI encontrados: ").append(outerCount).append("\n");
        out.append("Quadros DUML completos: ").append(frameCount).append("\n\n");

        if (haveCameraState) {
            out.append("=== CÂMERA ===\n");
            out.append("Modo: ").append(cameraModeName(cameraMode))
                    .append(" (").append(cameraMode).append(")\n");
            out.append("Conectada: ").append(cameraConnected ? "SIM" : "NÃO").append("\n");
            out.append("USB ativo: ").append(cameraUsbState ? "SIM" : "NÃO").append("\n");
            out.append("Hora sincronizada: ").append(cameraTimeSynced ? "SIM" : "NÃO").append("\n");
            out.append("Estado de foto: ").append(photoStateName(cameraPhotoState))
                    .append(" (").append(cameraPhotoState).append(")\n");
            out.append("Estado de gravação: ").append(recordStateName(cameraRecordState))
                    .append(" (").append(cameraRecordState).append(")\n");
            out.append("Cartão SD inserido: ").append(cameraSdInserted ? "SIM" : "NÃO").append("\n");
            out.append("Estado SD: ").append(sdStateName(cameraSdState))
                    .append(" (").append(cameraSdState).append(")\n");

            if (cameraSdTotal >= 0) {
                out.append("SD total (valor bruto): ").append(cameraSdTotal).append("\n");
                out.append("SD livre (valor bruto): ").append(cameraSdFree).append("\n");
                out.append("Fotos restantes: ").append(cameraRemainingShots).append("\n");
                out.append("Tempo restante (valor bruto): ").append(cameraRemainingTime).append("\n");
                out.append("Tempo de gravação atual: ").append(cameraRecordTime).append("\n");
                out.append("Histograma ativo: ")
                        .append(cameraHistogramEnabled ? "SIM" : "NÃO").append("\n");
                out.append("Tipo de câmera (código): 0x").append(hex2(cameraType)).append("\n");
                out.append("Versão do estado: ").append(cameraStateVersion).append("\n");
            }

            out.append("Flags: 0x")
                    .append(String.format(Locale.US, "%08X", cameraFlags))
                    .append("\n\n");
        }

        if (haveShotParams) {
            out.append("=== AJUSTES DE CAPTURA ===\n");
            out.append("ISO: ").append(isoName(isoCode))
                    .append(" (código ").append(isoCode).append(")\n");
            out.append("Exposição: ").append(exposureModeName(exposureModeCode))
                    .append(" (código ").append(exposureModeCode).append(")\n");
            out.append("Obturador bruto: 0x")
                    .append(String.format(Locale.US, "%04X", shutterRaw))
                    .append(" / decimal ").append(shutterDecimal).append("\n");
            out.append("Abertura bruta: 0x")
                    .append(String.format(Locale.US, "%04X", apertureRaw)).append("\n");
            out.append("Compensação EV (código bruto): ").append(exposureCompRaw).append("\n");
            out.append("Proporção da foto: ").append(imageRatioName(imageRatioCode))
                    .append(" (").append(imageRatioCode).append(")\n");
            out.append("Formato de vídeo (código): ").append(videoFormatCode).append("\n");
            out.append("FPS de vídeo (código): ").append(videoFpsCode).append("\n");
            out.append("FOV de vídeo (código): ").append(videoFovCode).append("\n");
            out.append("Balanço de branco (código): ").append(whiteBalanceCode).append("\n");
            out.append("Temperatura de cor (código): ").append(colorTempCode).append("\n");
            if (antiFlickerCode >= 0) {
                out.append("Anti-flicker (código): ").append(antiFlickerCode).append("\n");
            }
            out.append("\n");
        }

        if (haveGimbal) {
            out.append("=== GIMBAL ===\n");
            out.append(String.format(Locale.US,
                    "Pitch bruto: %.1f° | Roll bruto: %.1f° | Yaw bruto: %.1f°\n",
                    gimbalPitch, gimbalRoll, gimbalYaw));
            out.append("Modo: ").append(gimbalModeName(gimbalMode))
                    .append(" (").append(gimbalMode).append(")\n");
            out.append("Pitch no limite: ").append(gimbalPitchLimit ? "SIM" : "NÃO").append("\n");
            out.append("Roll no limite: ").append(gimbalRollLimit ? "SIM" : "NÃO").append("\n");
            out.append("Yaw no limite: ").append(gimbalYawLimit ? "SIM" : "NÃO").append("\n");
            out.append("Calibrando: ").append(gimbalCalibrating ? "SIM" : "NÃO").append("\n");
            out.append("Travado/stuck: ").append(gimbalStuck ? "SIM" : "NÃO").append("\n");
            if (gimbalVersion >= 0) {
                out.append("Versão do pacote do gimbal: ").append(gimbalVersion).append("\n");
            }
            out.append("Obs.: estes ângulos são coordenadas brutas do protocolo DJI.\n\n");
        }

        out.append("=== MENSAGENS OBSERVADAS ===\n");
        for (java.util.Map.Entry<String, Integer> entry : commandCounts.entrySet()) {
            out.append(entry.getValue()).append("x  ")
                    .append(entry.getKey()).append("\n");
        }

        out.append("\n0.6 mantém a escuta passiva e adiciona apenas um botão TX de CONSULTA (Camera Work Mode Get).");

        return out.toString();
    }

    private int readLe32Int(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private long readLe32Unsigned(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return -1;
        return ((long) data[offset] & 0xFFL)
                | (((long) data[offset + 1] & 0xFFL) << 8)
                | (((long) data[offset + 2] & 0xFFL) << 16)
                | (((long) data[offset + 3] & 0xFFL) << 24);
    }

    private int readLe16Unsigned(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readLe16Signed(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) return 0;
        int value = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        if ((value & 0x8000) != 0) value -= 0x10000;
        return value;
    }

    private String sourceName(int id) {
        switch (id & 0x1F) {
            case 1: return "Câmera";
            case 2: return "App";
            case 4: return "Gimbal";
            case 5: return "Placa central";
            default: return "Módulo 0x" + hex2(id);
        }
    }

    private String commandName(int cmdSet, int cmdId) {
        if (cmdSet == 0x02) {
            switch (cmdId) {
                case 0x01: return "Capturar foto";
                case 0x02: return "Gravar vídeo";
                case 0x10: return "Definir modo da câmera";
                case 0x11: return "Ler modo da câmera";
                case 0x18: return "Definir formato de vídeo";
                case 0x19: return "Ler formato de vídeo";
                case 0x28: return "Definir obturador";
                case 0x29: return "Ler obturador";
                case 0x2A: return "Definir ISO";
                case 0x2B: return "Ler ISO";
                case 0x2C: return "Definir balanço de branco";
                case 0x2D: return "Ler balanço de branco";
                case 0x2E: return "Definir EV";
                case 0x2F: return "Ler EV";
                case 0x60: return "Definir histograma";
                case 0x61: return "Ler histograma";
                case 0x70: return "Ler estado do sistema";
                case 0x71: return "Ler cartão SD";
                case 0x7C: return "Comando do obturador";
                case 0x80: return "Estado da câmera";
                case 0x81: return "Parâmetros de captura";
                case 0x83: return "Dados do histograma";
                case 0x87: return "Informações de captura/lente";
                case 0x88: return "Parâmetros de timelapse";
                case 0x8A: return "Parâmetros de FOV";
                default: return "Câmera cmd 0x" + hex2(cmdId);
            }
        }

        if (cmdSet == 0x04) {
            switch (cmdId) {
                case 0x01: return "Controle do gimbal";
                case 0x05: return "Posição/estado do gimbal";
                case 0x0A: return "Controle por ângulo";
                case 0x0C: return "Controle por velocidade";
                case 0x14: return "Controle de ângulo absoluto";
                case 0x15: return "Movimento do gimbal";
                case 0x1C: return "Tipo do gimbal";
                case 0x27: return "Estado anormal do gimbal";
                case 0x33: return "Bateria do gimbal";
                case 0x37: return "Parâmetros de timelapse do gimbal";
                case 0x38: return "Estado de timelapse do gimbal";
                case 0x4C: return "Reset/Modo do gimbal";
                case 0x57: return "Estado do joystick";
                case 0x58: return "Controle do joystick";
                default: return "Gimbal cmd 0x" + hex2(cmdId);
            }
        }

        if (cmdSet == 0x00) return "Geral cmd 0x" + hex2(cmdId);
        if (cmdSet == 0x05) return "Placa central cmd 0x" + hex2(cmdId);

        return "CmdSet 0x" + hex2(cmdSet) + " cmd 0x" + hex2(cmdId);
    }

    private String cameraModeName(int mode) {
        switch (mode) {
            case 0: return "FOTO";
            case 1: return "VÍDEO";
            case 2: return "PLAYBACK";
            case 3: return "TRANSCODE";
            case 4: return "AJUSTE";
            case 5: return "ECONOMIA";
            case 6: return "DOWNLOAD";
            case 7: return "NOVO PLAYBACK";
            default: return "DESCONHECIDO";
        }
    }

    private String photoStateName(int state) {
        switch (state) {
            case 0: return "Nenhuma captura";
            case 1: return "Foto única";
            case 2: return "Múltiplas";
            case 3: return "HDR";
            case 4: return "Panorama/FullView";
            default: return "Outro";
        }
    }

    private String recordStateName(int state) {
        switch (state) {
            case 0: return "Parada";
            case 1: return "Estado 1";
            case 2: return "Estado 2";
            case 3: return "Estado 3";
            default: return "Desconhecido";
        }
    }

    private String sdStateName(int state) {
        switch (state) {
            case 0: return "Normal";
            case 1: return "Sem cartão";
            case 2: return "Inválido";
            case 3: return "Protegido contra gravação";
            case 4: return "Não formatado";
            case 5: return "Formatando";
            case 6: return "Ilegal/incompatível";
            case 7: return "Ocupado";
            case 8: return "Cheio";
            case 9: return "Lento";
            case 10: return "Desconhecido";
            case 11: return "Índice máximo";
            case 12: return "Inicializando";
            case 13: return "Precisa formatar";
            case 14: return "Tentando recuperar arquivo";
            case 15: return "Ficou lento";
            default: return "Estado " + state;
        }
    }

    private String isoName(int code) {
        switch (code) {
            case 0: return "AUTO";
            case 1: return "AUTO HIGH";
            case 2: return "ISO 50";
            case 3: return "ISO 100";
            case 4: return "ISO 200";
            case 5: return "ISO 400";
            case 6: return "ISO 800";
            case 7: return "ISO 1600";
            case 8: return "ISO 3200";
            case 9: return "ISO 6400";
            case 10: return "ISO 12800";
            case 11: return "ISO 25600";
            default: return "Código " + code;
        }
    }

    private String exposureModeName(int mode) {
        switch (mode) {
            case 1: return "Program";
            case 2: return "Prioridade do obturador";
            case 3: return "Prioridade de abertura";
            case 4: return "Manual";
            case 7: return "Cine";
            default: return "Código " + mode;
        }
    }

    private String imageRatioName(int code) {
        switch (code) {
            case 0: return "4:3";
            case 1: return "16:9";
            case 2: return "3:2";
            default: return "Outro";
        }
    }

    private String gimbalModeName(int mode) {
        switch (mode) {
            case 0: return "Yaw sem Follow";
            case 1: return "FPV";
            case 2: return "Follow";
            case 3: return "Auto calibração";
            default: return "Desconhecido";
        }
    }

    private boolean looksLikePocketTransportHeader(byte[] data, int offset) {
        return offset + 8 <= data.length
                && (data[offset] & 0xFF) == 0x55
                && (data[offset + 1] & 0xFF) == 0xCC
                && (data[offset + 2] & 0xFF) == 0x49
                && (data[offset + 3] & 0xFF) == 0x57;
    }

    private long readLe32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return -1;
        }
        return ((long) data[offset] & 0xFFL)
                | (((long) data[offset + 1] & 0xFFL) << 8)
                | (((long) data[offset + 2] & 0xFFL) << 16)
                | (((long) data[offset + 3] & 0xFFL) << 24);
    }

    private String toHexRange(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length <= 0 || offset >= data.length) {
            return "";
        }

        int end = Math.min(data.length, offset + length);
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (i > offset) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private String hex2(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
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

    private void testOpenDevice(UsbDevice d) {
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(d);
            if (connection != null) {
                appendEvent("Canal USB Device abriu com sucesso. fd="
                        + connection.getFileDescriptor());
            } else {
                appendEvent("Android retornou null ao abrir USB Device.");
            }
        } catch (Throwable t) {
            appendEvent("Falha ao abrir USB Device: " + t.getClass().getSimpleName()
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

    private void copyDiagnostic() {
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Pocket Control diagnóstico", lastDiagnostic));
            Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendEvent(String message) {
        if (message == null) return;
        eventLog.append("\n[EVENTO] ").append(message).append("\n");
        renderLog();
    }

    private void appendEventFromWorker(String message) {
        runOnUiThread(() -> appendEvent(message));
    }

    private void renderLog() {
        lastDiagnostic = baseDiagnostic + eventLog.toString();
        if (logView != null) {
            logView.setText(lastDiagnostic);
        }
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

    private String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(data.length, 1024);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        if (data.length > limit) {
            sb.append(" ... (+").append(data.length - limit).append(" bytes)");
        }
        return sb.toString();
    }

    private String toAscii(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(data.length, 512);
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            if (b >= 32 && b <= 126) {
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        if (data.length > limit) {
            sb.append("...");
        }
        return sb.toString();
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
