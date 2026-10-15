# Original TV (player em APK)

App Android para o TV box abrir o player (`https://player.originalmeathouse.com.br`) em tela cheia, sem as variações do Chrome.

## O que o app resolve

- Tela cheia fixa, sem barras, sem zoom e sem a fonte do sistema mexendo no layout
- Dados do player ficam salvos (a configuração não se perde ao fechar)
- Vídeo e áudio tocam sozinhos
- Tela sempre ligada
- Sem internet: mostra aviso e tenta de novo a cada 10 segundos
- Recarrega o player a cada 6 horas (evita travar em operação 24h)
- Abre sozinho quando o TV box liga

## Gerar o APK

**Opção 1: GitHub (mais simples)**
1. Crie um repositório privado e envie esta pasta.
2. Aba *Actions* → *Gerar APK* → *Run workflow*.
3. Ao terminar, baixe `original-tv.apk` em *Artifacts*.

**Opção 2: Android Studio**
1. *File → Open* nesta pasta.
2. *Build → Build APK(s)*. O arquivo sai em `app/build/outputs/apk/debug/app-debug.apk`.

**Opção 3: terminal** (precisa de JDK 17 e Android SDK)
```
./gradlew assembleDebug
```

## Instalar no TV box

**Por pen drive:** copie o APK, abra pelo gerenciador de arquivos do TV box e instale (libere "fontes desconhecidas" quando pedir).

**Por rede (ADB):**
```
adb connect IP_DO_TV_BOX:5555
adb install -r original-tv.apk
```

## Configurar para abrir no boot

1. Abra o app uma vez.
2. Em *Configurações → Apps → Original TV → Permissões*, ative **Exibir sobre outros apps** (necessário no Android 10+).
3. Para garantir, defina o Original TV como **app de tela inicial** (*Configurações → Apps → Apps padrão → Tela inicial*). Assim o TV box liga direto no player, mesmo se faltar energia.

## Controle remoto

- **MENU**: recarrega o player
- **VOLTAR 5 vezes em 3 segundos**: fecha o app (manutenção). Se ele estiver como tela inicial, reabre sozinho; para sair de vez, troque o app de tela inicial nas configurações.

## Personalizar

- **Endereço do player:** `app/build.gradle`, linha `PLAYER_URL`.
- **Intervalo de recarga e tentativas:** constantes no topo de `MainActivity.java`.
- **Ícone e banner:** são provisórios (anel branco em fundo escuro). Troque `app/src/main/res/drawable-xhdpi/ic_launcher.png` (192×192) e `banner.png` (640×360) pelos arquivos do logo.
- **Detectar o app na página:** o user agent termina com `OriginalTV/1.0.0`.
