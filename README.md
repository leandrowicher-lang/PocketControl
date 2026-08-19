# Pocket Control 0.1 REAL

Protótipo de diagnóstico para **DJI Osmo Pocket 1 + Android**, feito sem login, sem senha,
sem internet e sem bibliotecas nativas. O objetivo desta versão é identificar como a Pocket
se apresenta ao Galaxy A13 pelo USB antes de implementar REC, foto, gimbal e live view.

## O que a versão 0.1 faz

- abre em Android 6+ (target Android 14);
- não usa bibliotecas `.so`, então o APK não fica preso a ARM 32 ou 64 bits;
- enumera USB Device e USB Accessory;
- mostra VID/PID, interfaces e endpoints quando disponíveis;
- reconhece como provável DJI quando os descritores indicam DJI/HG210;
- pede permissão USB;
- testa se o Android consegue abrir o canal USB;
- copia todo o diagnóstico para enviar ao ChatGPT.

## Compilar SEM Android Studio usando GitHub

1. Crie um repositório vazio no GitHub, por exemplo `PocketControl`.
2. Envie **todo o conteúdo desta pasta**, inclusive a pasta oculta `.github`.
3. Abra a aba **Actions** do repositório.
4. Abra **Build Pocket Control APK** e clique em **Run workflow**.
5. Quando terminar, abra a execução concluída.
6. Em **Artifacts**, baixe `PocketControl-0.1-debug-apk`.
7. Dentro do ZIP estará `app-debug.apk`.
8. Instale esse APK no Galaxy A13.

## Primeiro teste no telefone

1. Abra o Pocket Control sem conectar a câmera e confirme que ele permanece aberto.
2. Conecte a Osmo Pocket 1 ao USB-C e ligue a câmera.
3. Toque em **Atualizar USB**.
4. Se aparecer algum dispositivo, toque em **Pedir acesso** e aceite a permissão do Android.
5. Toque em **Copiar diagnóstico** e envie o texto ao ChatGPT.

## Importante

Esta versão **não envia nenhum comando para a câmera**. Ela apenas inspeciona o USB e abre/fecha
o canal para confirmar a comunicação. Isso reduz o risco de travar a câmera enquanto ainda não
sabemos quais interfaces/endpoints a Pocket apresenta ao Galaxy A13.
