// Display: onde se entra no grupo multicast e mostra cada jogada recebida.
// Uso: java Display [IP desta maquina] (quando nao tem IP, usa a interface de rede padrao do sistema)
// No tabuleiro: 0 = casa vazia, 1 = X (jogador que inicia), 2 = O (outro jogador)

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

public class Display {

    static final String GRUPO = "225.1.1.4";   // 225.1.1.NN
    static final int    PORTA = 8804;          // 88NN

    public static void main(String[] args) {
        // No Windows o Java usa IPv6 por padrao, o que atrapalha o multicast IPv4.
        System.setProperty("java.net.preferIPv4Stack", "true");

        try (MulticastSocket socket = new MulticastSocket(PORTA)) {
            // Se o IP é informado na chamada, entra no grupo pela interface desse IP. Se não é (rede == null) e sistema usa a interface padrao.
            NetworkInterface rede = null;
            if (args.length > 0) {
                rede = NetworkInterface.getByInetAddress(InetAddress.getByName(args[0]));
                if (rede == null) {
                    System.err.println("Nenhuma interface de rede com o IP " + args[0] + ".");
                    return;
                }
            }
            socket.joinGroup(new InetSocketAddress(InetAddress.getByName(GRUPO), PORTA), rede);

            System.out.println("=== DISPLAY - Jogo da Velha ===");
            System.out.println("Ouvindo " + GRUPO + ":" + PORTA + " pela interface "
                               + (rede == null ? "padrao do sistema" : rede.getName() + " (" + args[0] + ")"));
            System.out.println("Aguardando jogadas...\n");

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);
                socket.receive(pacote);
                String msg = new String(pacote.getData(), 0, pacote.getLength());
                String origem = pacote.getAddress().getHostAddress();
                try {
                    mostrar(msg, origem);
                } catch (Exception e) {
                    System.out.println("[" + origem + "] mensagem nao reconhecida: " + msg);
                }
            }
        } catch (Exception e) {
            System.err.println("Erro no display: " + e.getMessage());
        }
    }

    private static void mostrar(String msg, String origem) {
        int numero = Json.inteiro(msg, "Numero");
        int casa   = Json.inteiro(msg, "Jogada");
        int[] tab  = Json.vetor(msg, "Tabuleiro");
        String res = Json.texto(msg, "Resultado");
        char quem  = (numero % 2 == 0) ? 'X' : 'O';

        System.out.println("-------------------------------------------");
        System.out.println("Jogada #" + numero + "  |  " + quem + " na casa " + casa + "  |  de " + origem);
        System.out.println();
        for (int lin = 0; lin < 3; lin++) {
            StringBuilder sb = new StringBuilder("     ");
            for (int col = 0; col < 3; col++) {
                int i = lin * 3 + col;
                char c = tab[i] == 1 ? 'X' : tab[i] == 2 ? 'O' : (char) ('0' + i);
                sb.append(' ').append(c).append(' ');
                if (col < 2) sb.append('|');
            }
            System.out.println(sb);
            if (lin < 2) System.out.println("     ---+---+---");
        }
        System.out.println();
        if (res.equals("vit"))      System.out.println(">>> FIM DE JOGO: vitoria do " + quem + "! <<<");
        else if (res.equals("emp")) System.out.println(">>> FIM DE JOGO: empate! <<<");
        else                        System.out.println("(jogo em andamento)");
    }
}