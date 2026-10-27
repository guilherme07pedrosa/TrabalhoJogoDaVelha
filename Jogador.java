// Jogador: o jogo da velha distribuido
// 1. RMI: pede um oponente ao Servidor de Match (getOponent).
// 2. TCP: joga UMA partida trocando mensagens JSON com o oponente.
// 3. Multicast: publica cada jogada feita para os Displays.
// Uso: java Jogador <ipRegistry> <portaRegistry> <meuIP> <minhaPorta> [humano|auto] [grupoMulticast] [portaMulticast]
//      (sem grupo e porta, usa 225.1.1.12 e 8812)
 
// Convencoes do jogo:
// - Tabuleiro: 0 = vazia, 1 = X (quem inicia), 2 = O (outro jogador).
//  - Numero comeca em 0 e e incrementado por quem faz a jogada.
//  - Quem inicia conecta no oponente; o outro aceita a conexao.
//  - Jogada invalida recebida: devolve a mesma mensagem, sem incrementar.
//  - Fim de jogo: quem perde responde "der"; empate respondido com "emp".

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Arrays;

public class Jogador {

    // grupo e porta do multicast: padrao 225.1.1.NN e 88NN, podem ser trocados pelos argumentos
    static String grupoMc = "225.1.1.12";
    static int    portaMc = 8812;

    static final int TIMEOUT_JOGADA_MS = 5 * 60 * 1000;  // espera maxima pela jogada do oponente
    static final int TIMEOUT_ACEITE_MS = 30 * 1000;      // espera pelo aceite do resultado final
    static final int MAX_REJEICOES     = 5;              // rejeicoes seguidas antes de abandonar

    // estado da partida
    private final int[] tab = new int[9];
    private final int eu, ele;                // 1 = X, 2 = O
    private final boolean automatico;
    private int proximoNumero = 0;            // numero esperado para a proxima jogada
    private int rejeicoesSeguidas = 0;

    // ultima jogada que EU enviei (para desfazer se o oponente rejeitar)
    private int ultimoNumero = -1, ultimaCasa, valorAnterior;
    private String ultimoResultado = "jog";

    // comunicacao
    private final BufferedReader in;
    private final PrintWriter out;
    private final Socket sock;
    private final MulticastSocket mc;
    private final BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

    Jogador(int eu, boolean automatico, Socket sock, MulticastSocket mc) throws IOException {
        this.eu = eu;
        this.ele = 3 - eu;
        this.automatico = automatico;
        this.sock = sock;
        this.mc = mc;
        this.in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
        this.out = new PrintWriter(sock.getOutputStream(), true);
    }

    // main: match (RMI) e conexao (TCP)
    public static void main(String[] args) {
        // No Windows o Java usa IPv6 por padrao, o que atrapalha o multicast IPv4.
        System.setProperty("java.net.preferIPv4Stack", "true");

        if (args.length < 4) {
            System.err.println("Uso: java Jogador <ipRegistry> <portaRegistry> <meuIP> <minhaPorta> [humano|auto] [grupoMulticast] [portaMulticast]");
            return;
        }
        String ipReg = args[0];
        int portaReg = Integer.parseInt(args[1]);
        String meuIp = args[2];
        int minhaPorta = Integer.parseInt(args[3]);
        boolean automatico = args.length > 4 && args[4].equalsIgnoreCase("auto");
        if (args.length > 5) grupoMc = args[5];
        if (args.length > 6) portaMc = Integer.parseInt(args[6]);

        // Abre a escuta ANTES do match: o oponente pode conectar logo em seguida.
        try (ServerSocket escuta = new ServerSocket(minhaPorta)) {

            // match via RMI
            String ret;
            try {
                Registry reg = LocateRegistry.getRegistry(ipReg, portaReg);
                MatchIntf match = (MatchIntf) reg.lookup("MatchSrv");
                System.out.println("Procurando oponente no servidor " + ipReg + ":" + portaReg + " ...");
                ret = match.getOponent(Json.pedido(meuIp, minhaPorta, "tcp"));
            } catch (TimeoutMatchEx e) {
                System.out.println("Sem oponente: " + e.getMessage());
                return;
            } catch (Exception e) {
                System.out.println("Falha ao falar com o Servidor de Match: " + e.getMessage());
                return;
            }

            String ipOp = Json.texto(ret, "ip");
            int portaOp = Json.inteiro(ret, "porta");
            boolean inicia = Json.booleano(ret, "inicia");
            int eu = inicia ? 1 : 2;
            System.out.println("Oponente: " + ipOp + ":" + portaOp + ". Voce e o " + simbolo(eu)
                    + (inicia ? " e comeca." : " e espera a primeira jogada."));

            // conexao TCP: quem inicia conecta e o outro aceita
            escuta.setSoTimeout(60 * 1000);
            try (Socket s = inicia ? new Socket(ipOp, portaOp) : escuta.accept()) {
                MulticastSocket mc = abrirMulticast(meuIp);
                new Jogador(eu, automatico, s, mc).jogar(inicia);
                if (mc != null) mc.close();
            }
            System.out.println("Conexao encerrada. Ate a proxima!");

        } catch (SocketTimeoutException e) {
            System.out.println("O oponente nao se conectou a tempo. Encerrando.");
        } catch (Exception e) {
            System.out.println("Erro: " + e.getMessage());
        }
    }

    // socket para publicar no grupo multicast, saindo pela interface do IP informado
    private static MulticastSocket abrirMulticast(String meuIp) {
        try {
            MulticastSocket mc = new MulticastSocket();
            InetAddress ip = InetAddress.getByName(meuIp);
            // Com 127.0.0.1 (teste local) deixamos o sistema escolher a interface.
            if (!ip.isLoopbackAddress()) {
                NetworkInterface rede = NetworkInterface.getByInetAddress(ip);
                if (rede != null) mc.setNetworkInterface(rede);
            }
            return mc;
        } catch (Exception e) {
            System.out.println("[aviso] multicast indisponivel, jogando sem Display: " + e.getMessage());
            return null;
        }
    }

    //  a partida do jogo
    void jogar(boolean minhaVez) throws IOException {
        while (true) {

            // minha jogada
            if (minhaVez) {
                mostrarTabuleiro();
                int casa = escolherCasa();

                ultimoNumero = proximoNumero;
                ultimaCasa = casa;
                valorAnterior = tab[casa];
                tab[casa] = eu;
                ultimoResultado = vencedor() == eu ? "vit" : cheio() ? "emp" : "jog";

                String json = Json.jogada(ultimoNumero, casa, tab, ultimoResultado);
                enviar(json);
                publicar(json);
                proximoNumero++;

                mostrarTabuleiro();
                System.out.println("\nVoce (" + simbolo(eu) + ") jogou na casa " + casa + ".");
                if (ultimoResultado.equals("jog"))
                    System.out.println("Aguardando jogada do oponente...");
                else
                    System.out.println("Jogada final enviada. Aguardando confirmacao do oponente...");
                minhaVez = false;
                continue;
            }

            // resposta do oponente
            boolean fimEnviado = !ultimoResultado.equals("jog");
            String m = ler(fimEnviado ? TIMEOUT_ACEITE_MS : TIMEOUT_JOGADA_MS);
            if (m == null) {                                    // oponente sumiu
                if (fimEnviado) anunciarFim(false);
                return;
            }

            int numero;
            String resultado;
            try {
                numero = Json.inteiro(m, "Numero");
                resultado = Json.texto(m, "Resultado");
            } catch (Exception e) {
                System.out.println("[aviso] mensagem ignorada (formato invalido): " + m);
                continue;
            }

            // se mesmo numero da minha ultima jogada: aceite do fim de jogo ou rejeicao.
            if (numero == ultimoNumero) {
                if (fimEnviado && (resultado.equals("der") || resultado.equals("emp"))) {
                    anunciarFim(true);
                    return;
                }
                tab[ultimaCasa] = valorAnterior;                // desfaz a jogada rejeitada
                proximoNumero = ultimoNumero;
                ultimoResultado = "jog";
                System.out.println("\n!!! O oponente REJEITOU sua jogada na casa " + ultimaCasa
                        + " (mensagem devolvida). Jogue novamente.");
                if (++rejeicoesSeguidas >= MAX_REJEICOES) {
                    System.out.println("Muitas rejeicoes seguidas. Abandonando a partida.");
                    return;
                }
                minhaVez = true;
                continue;
            }

            // jogada nova do oponente: valida antes de aceitar.
            String erro = validar(m, numero);
            if (erro != null) {
                enviar(m);                                      // devolve a mesma mensagem
                System.out.println("\n!!! Jogada INVALIDA do oponente (" + erro
                        + "). Mensagem devolvida, aguardando correcao.");
                if (++rejeicoesSeguidas >= MAX_REJEICOES) {
                    System.out.println("O oponente insiste em jogadas invalidas. Abandonando a partida.");
                    return;
                }
                continue;
            }

            rejeicoesSeguidas = 0;
            int casa = Json.inteiro(m, "Jogada");
            tab[casa] = ele;
            proximoNumero++;
            System.out.println("\nOponente (" + simbolo(ele) + ") jogou na casa " + casa + ".");

            if (vencedor() == ele) {
                mostrarTabuleiro();
                System.out.println("\n=== VOCE PERDEU. Vitoria do " + simbolo(ele) + ". ===");
                enviar(Json.jogada(numero, casa, tab, "der"));     // aceita o resultado
                return;
            }
            if (cheio()) {
                mostrarTabuleiro();
                System.out.println("\n=== EMPATE! ===");
                enviar(Json.jogada(numero, casa, tab, "emp"));
                return;
            }
            minhaVez = true;
        }
    }

    // confere a jogada recebida, devolve null se estiver correta ou o motivo do erro se nao
    private String validar(String m, int numero) {
        int casa;
        int[] t;
        try {
            casa = Json.inteiro(m, "Jogada");
            t = Json.vetor(m, "Tabuleiro");
        } catch (Exception e) {
            return "mensagem mal formada";
        }
        if (numero != proximoNumero) return "numero da jogada " + numero + ", esperado " + proximoNumero;
        if (casa < 0 || casa > 8)    return "casa " + casa + " nao existe";
        if (tab[casa] != 0)          return "casa " + casa + " ja ocupada";

        int[] esperado = tab.clone();
        esperado[casa] = ele;
        if (!Arrays.equals(esperado, t)) return "tabuleiro enviado nao confere com o local";
        return null;
    }

    // escolha da jogada
    private int escolherCasa() throws IOException {
        if (automatico) {
            pausa(800);                                  // da tempo de acompanhar a partida
            for (int i = 0; i < 9; i++) if (tab[i] == 0) return i;
        }
        System.out.println("\nSua vez (voce e o " + simbolo(eu) + ").");
        while (true) {
            System.out.print("Escolha uma casa [0-8]: ");
            String linha = teclado.readLine();
            if (linha == null) throw new IOException("entrada encerrada");
            int c;
            try {
                c = Integer.parseInt(linha.trim());
            } catch (NumberFormatException e) {
                System.out.println("\"" + linha.trim() + "\" nao e um numero. Digite um numero de 0 a 8.");
                continue;
            }
            if (c < 0 || c > 8)   System.out.println("A casa " + c + " nao existe. Use de 0 a 8.");
            else if (tab[c] != 0) System.out.println("A casa " + c + " ja esta ocupada. Escolha outra.");
            else return c;
        }
    }

    //  regras
    private static final int[][] LINHAS = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8},      // linhas
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8},      // colunas
        {0, 4, 8}, {2, 4, 6}                  // diagonais
    };

    // devolve 1 ou 2 se alguem completou uma linha, ou 0
    private int vencedor() {
        for (int[] l : LINHAS)
            if (tab[l[0]] != 0 && tab[l[0]] == tab[l[1]] && tab[l[1]] == tab[l[2]]) return tab[l[0]];
        return 0;
    }

    private boolean cheio() {
        for (int v : tab) if (v == 0) return false;
        return true;
    }

    // comunicacao
    private void enviar(String json) {
        out.println(json);
    }

    private void publicar(String json) {
        if (mc == null) return;
        try {
            byte[] dados = json.getBytes();
            mc.send(new DatagramPacket(dados, dados.length, InetAddress.getByName(grupoMc), portaMc));
        } catch (IOException e) {
            System.out.println("[aviso] falha ao publicar no Display: " + e.getMessage());
        }
    }

    // le uma linha do oponente e devolve null se ele demorou demais ou desconectou
    private String ler(int timeoutMs) throws IOException {
        try {
            sock.setSoTimeout(timeoutMs);
            String linha = in.readLine();
            if (linha == null) System.out.println("\nO oponente desconectou.");
            return linha;
        } catch (SocketTimeoutException e) {
            System.out.println("\nO oponente nao respondeu em " + timeoutMs / 1000 + "s. Encerrando a partida.");
            return null;
        }
    }

    // exibicao
    static char simbolo(int v) {
        return v == 1 ? 'X' : v == 2 ? 'O' : ' ';
    }

    private void anunciarFim(boolean confirmado) {
        String extra = confirmado ? " (confirmado pelo oponente)" : " (sem confirmacao do oponente)";
        if (ultimoResultado.equals("vit")) System.out.println("\n=== VOCE VENCEU!" + extra + " ===");
        else                               System.out.println("\n=== EMPATE!" + extra + " ===");
    }

    private void mostrarTabuleiro() {
        System.out.println();
        for (int lin = 0; lin < 3; lin++) {
            StringBuilder sb = new StringBuilder("   ");
            for (int col = 0; col < 3; col++) {
                int i = lin * 3 + col;
                char c = tab[i] == 0 ? (char) ('0' + i) : simbolo(tab[i]);
                sb.append(' ').append(c).append(' ');
                if (col < 2) sb.append('|');
            }
            System.out.println(sb);
            if (lin < 2) System.out.println("   ---+---+---");
        }
    }

    private static void pausa(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}